"""Idempotent initialization with explicit content and migration checks."""
import hashlib
import json
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
        raise Failure('HTTP ' + str(error.code) + ' from ' + urllib.parse.urlsplit(url).path) from None


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

    def login(self, initialize=False):
        values = {'username': 'nacos', 'password': self.e.credentials['nacos']}
        try:
            self.token = self.call('POST', 'auth/user/login', values)['accessToken']
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


def initialize_nacos(e):
    api = Nacos(e)
    api.login(initialize=True)
    existing = {n['namespace'] for n in api.call('GET', 'admin/core/namespace/list')}
    rows = []
    for n in e.args.slots:
        namespace = e.args.base_namespace if n == 0 else e.args.namespace_prefix + str(n)
        if namespace not in existing:
            api.call('POST', 'admin/core/namespace', dict(namespaceId=namespace, namespaceName=namespace,
                                                        namespaceDesc='Local application environment'))
        items = {(c['groupName'], c['dataId']) for c in api.items(namespace)}
        for (group, data_id), content in CONFIGS.items():
            params = dict(namespaceId=namespace, groupName=group, dataId=data_id)
            if (group, data_id) in items:
                actual = api.call('GET', 'admin/cs/config', params)
                if actual['content'] != content or actual.get('type') != 'yaml':
                    raise Failure('Nacos configuration conflict: ' + namespace + '/' + group + '/' + data_id)
            else:
                api.call('POST', 'admin/cs/config', {**params, 'content': content, 'type': 'yaml'})
            actual = api.call('GET', 'admin/cs/config', params)
            if actual['content'] != content or actual.get('type') != 'yaml':
                raise Failure('Nacos configuration readback failed')
            rows.append(dict(namespace=namespace, group=group, data_id=data_id,
                             sha256=hashlib.sha256(content.encode()).hexdigest(), bytes=len(content.encode()), type='yaml'))
    protected_write(e.state / 'nacos-initialization.json', json.dumps(rows, indent=2) + '\n')


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
    print('Initialization complete; existing credentials and application data retained')
