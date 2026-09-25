"""Idempotent initialization with explicit content and migration checks."""
import hashlib
import json
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request
from middleware import Failure, protected_write

CONFIGS = {
    ('DEFAULT_GROUP', 'mars-cloud-auth-service.yaml'): 'mars:\n  auth:\n    config-revision: local-1\n',
    ('COMMON', 'shared-common.yaml'): 'mars:\n  config-revision: local-1\n',
    ('DEFAULT_GROUP', 'mars-cloud-gateway.yaml'): 'mars:\n  gateway:\n    config-revision: local-1\n',
    ('DEFAULT_GROUP', 'mars-cloud-upms-service.yaml'): 'mars:\n  upms:\n    nacos:\n      config-revision: local-1\n',
    ('DEFAULT_GROUP', 'mars-cloud-sample-service.yaml'): 'mars:\n  sample:\n    config-revision: local-1\n',
    ('DEFAULT_GROUP', 'mars-cloud-monitor.yaml'): 'mars:\n  monitor:\n    config-revision: local-1\n',
}
# The gateway refuses to start without its Sentinel rule configurations; the local baseline lives in
# config/sentinel so the gateway tooling and this seed read the same files.
SENTINEL_GROUP = 'SENTINEL_GROUP'
for _rules in sorted((Path(__file__).resolve().parent / 'config' / 'sentinel').glob('*-rules.json')):
    CONFIGS[(SENTINEL_GROUP, _rules.name)] = _rules.read_text(encoding='utf-8')


def config_type(data_id):
    """Nacos configuration type of a seed, taken from the data ID extension."""
    return 'json' if data_id.endswith('.json') else 'yaml'


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


def numbered_namespaces(e):
    """Namespaces of the numbered environments selected by --slots (never the base namespace)."""
    return {e.args.namespace_prefix + str(n) for n in e.args.slots if n != 0}


def expected_nacos(e):
    """Seed content for every namespace, with ``checked`` saying whether existing content is compared.

    A numbered environment namespace belongs to the development workflow that fills it from a base
    namespace, so initialization only creates what is missing there and never compares or overwrites
    existing content. The base namespace and every other namespace named by the selected configuration
    keep the strict rule: existing content and type must equal the template or the selection.
    """
    numbered = numbered_namespaces(e)
    expected = {}
    for n in e.args.slots:
        namespace = e.args.base_namespace if n == 0 else e.args.namespace_prefix + str(n)
        for (group, data_id), content in CONFIGS.items():
            expected[(namespace, group, data_id)] = dict(content=content, type=config_type(data_id),
                                                         checked=namespace not in numbered)
    for row in e.nacos_configurations:
        expected[(row['namespace'], row['group'], row['data_id'])] = dict(
            content=row['content'], type=row['type'], checked=row['namespace'] not in numbered)
    return expected


def differs(actual, value):
    return any(actual.get(field) != value[field] for field in ('content', 'type'))


def read_back(api, key, value, mismatch):
    """Read one configuration and apply the rule of its namespace.

    A checked namespace must hold the expected content and type. A numbered environment namespace only
    needs the configuration to exist with nonempty content; Nacos answers 404 when it is missing.
    """
    try:
        actual = api.call('GET', 'admin/cs/config', dict(namespaceId=key[0], groupName=key[1], dataId=key[2]))
    except HttpFailure as error:
        if error.status != 404:
            raise
        raise Failure('Nacos configuration is missing; run up to create it: ' + '/'.join(key)) from None
    if value['checked']:
        if differs(actual, value):
            raise Failure(mismatch + '/'.join(key))
    elif not str(actual.get('content') or '').strip():
        raise Failure('Nacos configuration is empty: ' + '/'.join(key))
    return actual


def verify_selected_configurations(e, api):
    for key, value in expected_nacos(e).items():
        read_back(api, key, value, 'Nacos initialization content differs: ')


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
            if not expected[key]['checked']:
                continue  # Existing content of a numbered environment namespace is never compared.
            actual = api.call('GET', 'admin/cs/config', dict(namespaceId=key[0], groupName=key[1], dataId=key[2]))
            if differs(actual, expected[key]):
                raise Failure('Nacos configuration conflict: ' + '/'.join(key))
    for namespace in namespaces:
        if namespace not in existing:
            api.call('POST', 'admin/core/namespace', dict(namespaceId=namespace, namespaceName=namespace,
                                                        namespaceDesc='Local application environment'))
    rows = []
    for key, value in sorted(expected.items()):
        if key not in present:
            api.call('POST', 'admin/cs/config', dict(namespaceId=key[0], groupName=key[1], dataId=key[2],
                                                    content=value['content'], type=value['type']))
        actual = read_back(api, key, value, 'Nacos configuration readback failed: ')
        # Rows of a numbered environment namespace record what was read, not the seed that may differ.
        content = actual['content'].encode()
        rows.append(dict(namespace=key[0], group=key[1], data_id=key[2],
                         sha256=hashlib.sha256(content).hexdigest(), bytes=len(content), type=actual.get('type'),
                         content_checked=value['checked']))
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
    expected.add(('/v3/admin/core/namespace', 'r'))
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
