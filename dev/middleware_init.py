"""Idempotent initialization with explicit content and migration checks."""
import hashlib
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request
from middleware import Failure, protected_write

CONFIGS = {
    ('COMMON', 'shared-common.yaml'): 'mars:\n  config-revision: local-1\n',
    ('DEFAULT_GROUP', 'mars-cloud-gateway.yaml'): 'mars:\n  gateway:\n    config-revision: local-1\n',
    ('DEFAULT_GROUP', 'mars-cloud-upms-service.yaml'): 'mars:\n  upms:\n    nacos:\n      config-revision: local-1\n',
    ('DEFAULT_GROUP', 'mars-cloud-sample-service.yaml'): 'mars:\n  sample:\n    config-revision: local-1\n',
}


class HttpFailure(Failure):
    def __init__(self, status, path):
        self.status = status
        super().__init__('HTTP ' + str(status) + ' from ' + path)


def request(url, method='GET', params=None, headers=None, json_body=None):
    data = None
    headers = dict(headers or {})
    if params:
        encoded = urllib.parse.urlencode(params)
        if method in ('GET', 'DELETE'):
            url += '?' + encoded
        else:
            data = encoded.encode()
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
    if json_body is not None:
        data = json.dumps(json_body).encode()
        headers['Content-Type'] = 'application/json'
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=headers), timeout=20) as result:
            body = result.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        raise HttpFailure(error.code, urllib.parse.urlsplit(url).path) from None


class Nacos:
    def __init__(self, environment):
        self.e = environment
        self.base = 'http://' + environment.args.bind + ':' + str(environment.ports['nacos_http'])
        self.token = None

    def call(self, method, path, params=None):
        body = request(self.base + '/nacos/v3/' + path, method, params,
                       {'accessToken': self.token} if self.token else {})
        if 'code' in body and body['code'] != 0:
            raise Failure('Nacos request failed: ' + path + ' code=' + str(body['code']))
        return body.get('data', body)

    def login(self, initialize=False, client=False):
        if client and 'nacos_client' not in self.e.credentials:
            raise Failure('Nacos client credentials have not been initialized')
        values = {'username': 'nacos', 'password': self.e.credentials['nacos']}
        if client:
            values = dict(username=client_name(self.e), password=self.e.credentials['nacos_client'])
        try:
            result = self.call('POST', 'auth/user/login', values)
            if client and result.get('globalAdmin') is not False:
                raise Failure('The application client must not be an administrator')
            self.token = result['accessToken']
        except Failure:
            if not initialize:
                raise
            # This endpoint creates an absent administrator; it cannot reset an existing one.
            self.call('POST', 'auth/user/admin', {'password': values['password']})
            self.token = self.call('POST', 'auth/user/login', values)['accessToken']

    def items(self, namespace):
        result, page = [], 1
        while True:
            data = self.call('GET', 'admin/cs/config/list',
                             dict(namespaceId=namespace, pageNo=page, pageSize=100))
            result.extend(data['pageItems'])
            if len(result) >= data['totalCount']:
                return result
            page += 1
            if not data['pageItems']:
                raise Failure('Nacos pagination ended before all configurations were read')


def expected_nacos(e):
    expected = {}
    for n in e.args.slots:
        namespace = e.args.base_namespace if n == 0 else e.args.namespace_prefix + str(n)
        for (group, data_id), content in CONFIGS.items():
            expected[(namespace, group, data_id)] = dict(content=content, type='yaml')
    for row in e.nacos_configurations:
        expected[(row['namespace'], row['group'], row['data_id'])] = dict(content=row['content'], type=row['type'])
    return expected


def verify_selected_configurations(e, api):
    for (namespace, group, data_id), value in expected_nacos(e).items():
        actual = api.call('GET', 'admin/cs/config', dict(namespaceId=namespace, groupName=group, dataId=data_id))
        if any(actual.get(field) != value[field] for field in ('content', 'type')):
            raise Failure('Nacos initialization content differs: ' + '/'.join((namespace, group, data_id)))


def initialize_nacos(e):
    api = Nacos(e)
    api.login(initialize=True)
    existing = {n['namespace'] for n in api.call('GET', 'admin/core/namespace/list')}
    expected = expected_nacos(e)
    namespaces = sorted({key[0] for key in expected})
    present = set()
    # Inspect the complete target set before creating any namespace or configuration.
    for namespace in namespaces:
        if namespace not in existing:
            continue
        for item in api.items(namespace):
            key = (namespace, item['groupName'], item['dataId'])
            if key not in expected:
                continue
            present.add(key)
            actual = api.call('GET', 'admin/cs/config', dict(namespaceId=key[0], groupName=key[1], dataId=key[2]))
            if any(actual.get(field) != expected[key][field] for field in ('content', 'type')):
                raise Failure('Nacos configuration conflict: ' + '/'.join(key))
    for namespace in namespaces:
        if namespace not in existing:
            api.call('POST', 'admin/core/namespace', dict(namespaceId=namespace, namespaceName=namespace,
                                                        namespaceDesc='Local application environment'))
    rows = []
    for (namespace, group, data_id), value in sorted(expected.items()):
        params = dict(namespaceId=namespace, groupName=group, dataId=data_id)
        if (namespace, group, data_id) not in present:
            api.call('POST', 'admin/cs/config', {**params, **value})
        actual = api.call('GET', 'admin/cs/config', params)
        if any(actual.get(field) != value[field] for field in ('content', 'type')):
            raise Failure('Nacos configuration readback failed')
        content = value['content'].encode()
        rows.append(dict(namespace=namespace, group=group, data_id=data_id,
                         sha256=hashlib.sha256(content).hexdigest(), bytes=len(content), type=value['type']))
    protected_write(e.state / 'nacos-initialization.json', json.dumps(rows, indent=2) + '\n')


def client_name(e):
    return 'local-client-' + e.owner[:12]


def paged(api, path, **params):
    result, page, total = [], 1, None
    while True:
        data = api.call('GET', path, dict(params, pageNo=page, pageSize=100))
        if total is None:
            total = data['totalCount']
        if data['totalCount'] != total:
            raise Failure('Nacos authorization list changed during pagination')
        result.extend(data['pageItems'])
        if len(result) == total:
            return result
        if not data['pageItems'] or len(result) > total:
            raise Failure('Incomplete Nacos authorization pagination')
        page += 1


def initialize_nacos_client(e):
    from middleware_nacos_config import atomic_record
    api = Nacos(e)
    api.login()
    username = client_name(e)
    role = username
    users = paged(api, 'auth/user/list', username=username)
    if 'nacos_client' not in e.credentials:
        if users:
            raise Failure('An existing client account has no locally owned credentials')
        e.credentials['nacos_client'] = secrets.token_hex(24)
        atomic_record(e.state / 'credentials.json', e.credentials)
    if not users:
        api.call('POST', 'auth/user', dict(username=username, password=e.credentials['nacos_client']))
    client = Nacos(e)
    client.login(client=True)
    roles = paged(api, 'auth/role/list', username=username)
    if any(item['role'] != role for item in roles):
        raise Failure('The client account has unexpected roles')
    if not roles:
        api.call('POST', 'auth/role', dict(role=role, username=username))
    expected = {(ns + ':*:*', 'rw') for ns, _, _ in expected_nacos(e)}
    # Listing namespaces supports local environment discovery; account administration is separate.
    expected.add(('public:*:console//v3/admin/core/namespace', 'r'))
    permissions = paged(api, 'auth/permission/list', role=role)
    actual = {(item['resource'], item['action']) for item in permissions}
    if actual - expected:
        raise Failure('The client role has unexpected permissions')
    for resource, action in sorted(expected - actual):
        api.call('POST', 'auth/permission', dict(role=role, resource=resource, action=action))
    # Auth caches may need a short interval to observe new grants.
    from middleware_verify import retry
    retry(lambda: verify_nacos_client(e), timeout=45)


def verify_nacos_client(e):
    client = Nacos(e)
    client.login(client=True)
    client.call('GET', 'admin/core/namespace/list')
    verify_selected_configurations(e, client)
    for path in ('auth/user/list', 'auth/role/list', 'auth/permission/list'):
        try:
            client.call('GET', path, dict(pageNo=1, pageSize=1))
        except HttpFailure as error:
            if error.status != 403:
                raise
        else:
            raise Failure('The application client can access account administration')


def initialize_mysql(e):
    e.sql('CREATE DATABASE IF NOT EXISTS local_middleware;\n'
          'CREATE TABLE IF NOT EXISTS local_middleware.migrations '
          '(version VARCHAR(100) PRIMARY KEY, digest CHAR(64) NOT NULL, applied BOOLEAN NOT NULL);\n')
    for name, password in e.credentials['databases'].items():
        # Names and passwords were generated from restricted identifiers and hexadecimal random data.
        e.sql(f'CREATE DATABASE IF NOT EXISTS `mars_{name}` CHARACTER SET utf8mb4;\n'
              f"CREATE USER IF NOT EXISTS 'mars_{name}'@'%' IDENTIFIED BY '{password}';\n"
              f"GRANT ALL PRIVILEGES ON `mars_{name}`.* TO 'mars_{name}'@'%';\n")
    version = '001-xxl-schema'
    content = (e.source / 'init/001-xxl-schema.sql').read_text()
    digest = hashlib.sha256(content.encode()).hexdigest()
    old = e.sql(f"SELECT digest,applied FROM local_middleware.migrations WHERE version='{version}';")
    if old and old.split('\t')[0] != digest:
        raise Failure('An already registered migration has changed')
    if not old:
        unexpected = e.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='xxl_job';")
        if unexpected != '0':
            raise Failure('Existing scheduler tables lack an owned migration record')
        e.sql(f"INSERT INTO local_middleware.migrations VALUES ('{version}','{digest}',false);")
    if not old or old.split('\t')[1] != '1':
        e.sql('CREATE DATABASE IF NOT EXISTS xxl_job CHARACTER SET utf8mb4;\nUSE xxl_job;\n' + content)
        pwd_hash = hashlib.sha256(e.credentials['xxl'].encode()).hexdigest()
        statements = ['START TRANSACTION;',
                      "INSERT IGNORE INTO xxl_job.xxl_job_lock (lock_name) VALUES ('schedule_lock');",
                      "INSERT IGNORE INTO xxl_job.xxl_job_user (id,username,password,role) "
                      f"VALUES (1,'admin','{pwd_hash}',1);"]
        for n in e.args.slots:
            group_id = n + 1
            app = (f's{n}-' if n else '') + 'local-verification'
            statements.append('INSERT IGNORE INTO xxl_job.xxl_job_group '
                              '(id,app_name,title,address_type,address_list,update_time) '
                              f"VALUES ({group_id},'{app}','Local verification',1,NULL,NOW());")
            statements.append('INSERT IGNORE INTO xxl_job.xxl_job_info '
                              '(id,job_group,job_desc,add_time,update_time,author,schedule_type,schedule_conf,'
                              'misfire_strategy,executor_route_strategy,executor_handler,executor_param,'
                              'executor_block_strategy,glue_type,trigger_status) '
                              f"VALUES ({group_id},{group_id},'Local verification',NOW(),NOW(),'local',"
                              "'NONE','','DO_NOTHING','FIRST','localVerification','','SERIAL_EXECUTION','BEAN',0);")
        statements.extend([f"UPDATE local_middleware.migrations SET applied=true WHERE version='{version}';", 'COMMIT;'])
        e.sql('\n'.join(statements))
    e.sql("CREATE USER IF NOT EXISTS 'xxl_job'@'%' IDENTIFIED BY '" + e.credentials['xxl_db'] + "';\n"
          'GRANT SELECT,INSERT,UPDATE,DELETE ON xxl_job.* TO \'xxl_job\'@\'%\';\n')
    active = e.sql("SELECT COUNT(*) FROM xxl_job.xxl_job_info WHERE executor_handler='localVerification' AND trigger_status<>0;")
    if active != '0':
        raise Failure('Local verification scheduler tasks must remain stopped')


def initialize(e):
    initialize_mysql(e)
    initialize_nacos(e)
    initialize_nacos_client(e)
    print('Initialization complete; existing credentials and application data retained')
