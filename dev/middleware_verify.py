"""Check real middleware operations and retain non-secret verification evidence."""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from middleware import Failure, SERVICES, protected_write
from middleware_init import HttpFailure, Nacos, request, verify_selected_configurations, verify_nacos_client


def retry(action, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        try:
            return action()
        except (Failure, OSError, KeyError, AssertionError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)


def require(value, message):
    if not value:
        raise Failure(message)


def verify_limits(e, rows):
    definition = json.loads(e.compose_file.read_text())['services']
    expected_hashes = dict(line.split() for line in e.compose('config', '--hash', '*').stdout.splitlines())
    env = e.compose_env()
    for row in rows:
        info = json.loads(e.docker('inspect', e.args.project + '-' + row['service'] + '-1').stdout)[0]
        labels = info['Config']['Labels']
        require(labels.get('io.mars.middleware.source') == e.source_digest(), 'Running container belongs to another source candidate')
        require(labels.get('com.docker.compose.config-hash') == expected_hashes[row['service']], 'Running Compose configuration differs')
        image_key = definition[row['service']]['image'][2:-3]
        expected_image = env[image_key]
        image_info = json.loads(e.docker('image', 'inspect', expected_image).stdout)[0]
        require(info['Image'] == image_info['Id'], 'Running image differs from the fixed image')
        if row['service'] == 'jaeger':
            require((image_info['Config'].get('Labels') or {}).get('io.mars.middleware.jaeger-build') == e.jaeger_build_id(),
                    'Jaeger image build inputs differ')
        platform = 'linux/amd64' if row['service'] == 'xxl-job-admin' else e.args.platform
        require(image_info['Os'] + '/' + image_info['Architecture'] == platform, 'Running image platform differs')
        expected = definition[row['service']]
        memory = int(expected['mem_limit'][:-1]) * 1024 * 1024
        require(row['memory'] == memory and row['swap'] == memory, 'Memory limits differ from the declared budget')
        require(row['cpus'] == int(expected['cpus'] * 1e9) and row['pids'] == expected['pids_limit'], 'CPU or process limits differ')
        require(row['restart'] == 'always' and row['log']['Type'] == 'json-file'
                and row['log']['Config'].get('max-size') == '10m'
                and row['log']['Config'].get('max-file') == '3', 'Restart or log policy differs')


def verify_nacos(e, marker):
    api = Nacos(e)
    api.login()
    verify_selected_configurations(e, api)
    verify_nacos_client(e)
    for n in e.args.slots:
        namespace = e.args.base_namespace if not n else e.args.namespace_prefix + str(n)
        params = dict(namespaceId=namespace, groupName='LOCAL_VERIFY', dataId=marker['id'] + '.yaml')
        value = f'verification: {marker["id"]}-{n}\n'
        exists = any(item['dataId'] == params['dataId'] and item['groupName'] == params['groupName'] for item in api.items(namespace))
        if exists:
            require(api.call('GET', 'admin/cs/config', params)['content'] == value, 'Persisted Nacos data changed')
        elif marker['existing']:
            raise Failure('Persisted Nacos verification configuration is missing')
        else:
            api.call('POST', 'admin/cs/config', {**params, 'content': value, 'type': 'yaml'})
        require(api.call('GET', 'admin/cs/config', params)['content'] == value, 'Nacos readback failed')


def verify_mysql(e, marker):
    for name, password in e.credentials['databases'].items():
        filename = 'mysql-' + name + '.cnf'
        protected_write(e.state / filename, f'[client]\nuser=mars_{name}\npassword={password}\n')
        # SQL client configuration travels over stdin, never in process arguments.
        e.compose('exec', '-T', 'mysql', 'sh', '-c', 'umask 077; cat > /tmp/' + filename,
                  data=(e.state / filename).read_text(), phase='install database probe credentials')
        def sql(query, check=True):
            return e.compose('exec', '-T', 'mysql', 'mysql', '--defaults-extra-file=/tmp/' + filename,
                             '--batch', '--skip-column-names', data=query, check=check, phase='database isolation probe')
        try:
            table = 'mars_' + name + '.local_verification'
            query = f'CREATE TABLE IF NOT EXISTS {table} (id VARCHAR(64) PRIMARY KEY, value VARCHAR(100));\n'
            query += f"SELECT value FROM {table} WHERE id='{marker['id']}';"
            old = sql(query).stdout.strip()
            value = marker['id'] + '-' + name
            if marker['existing']:
                require(old == value, 'Persistent MySQL record is missing or changed')
            elif not old:
                sql(f"INSERT INTO {table} VALUES ('{marker['id']}','{value}');")
            require(sql(f"SELECT value FROM {table} WHERE id='{marker['id']}';").stdout.strip() == value, 'MySQL readback failed')
            require(sql('SELECT * FROM xxl_job.xxl_job_user;', check=False).returncode != 0, 'Application database account can access scheduler data')
        finally:
            e.compose('exec', '-T', 'mysql', 'rm', '-f', '/tmp/' + filename, phase='remove database probe credentials')


def redis(e, slot, *args):
    return e.compose('exec', '-T', 'redis', 'sh', '-c',
                     'export REDISCLI_AUTH="$(cat /run/local/redis-password)"; exec redis-cli --raw "$@"',
                     'redis-probe', '-n', str(slot), *args, phase='Redis probe').stdout.strip()


def verify_redis(e, marker):
    key = 'local-verification:' + marker['id']
    for n in e.args.slots:
        expected = marker['id'] + '-' + str(n)
        old = redis(e, n, 'GET', key)
        if marker['existing']:
            require(old == expected, 'Persistent Redis key is missing or changed')
        else:
            require(redis(e, n, 'SET', key, expected, 'NX') in ('OK', ''), 'Redis write failed')
        require(redis(e, n, 'GET', key) == expected, 'Redis database isolation failed')


def verify_observability(e, marker):
    base = 'http://' + e.args.bind + ':'
    trace = marker['trace']
    jaeger = base + str(e.ports['jaeger_ui'])
    loki = base + str(e.ports['loki'])
    # Retention expiry is explicit; it does not count as a restart persistence check.
    require(time.time_ns() - marker['timestamp'] < 47 * 3600 * 10**9,
            'Verification data reached its retention limit; archive this report and start a new verification cycle')
    if not marker['existing']:
        stamp = marker['timestamp']
        request(base + str(e.ports['otlp_http']) + '/v1/traces', 'POST', json_body={
            'resourceSpans': [{'resource': {'attributes': [{'key': 'service.name', 'value': {'stringValue': 'local-verification'}}]},
                               'scopeSpans': [{'spans': [{'traceId': trace, 'spanId': marker['span'],
                                                        'name': marker['id'], 'kind': 1,
                                                        'startTimeUnixNano': str(stamp), 'endTimeUnixNano': str(stamp + 1000000)}]}]}]})
        request(loki + '/loki/api/v1/push', 'POST', json_body={
            'streams': [{'stream': {'app': 'local-verification', 'run': marker['id']},
                         'values': [[str(stamp), marker['id']]]}]})
    def trace_check():
        body = request(jaeger + '/api/traces/' + trace)
        require(bool(body.get('data')) and body['data'][0]['traceID'] == trace, 'Jaeger trace query failed')
    retry(trace_check)
    def search_check():
        require('local-verification' in request(jaeger + '/api/services')['data'], 'Jaeger service list is missing the probe')
        operations = request(jaeger + '/api/services/local-verification/operations')['data']
        require(marker['id'] in operations, 'Jaeger operation list is missing the probe')
        query = urllib.parse.urlencode({'service': 'local-verification', 'operation': marker['id'],
                                       'start': str(marker['timestamp'] // 1000 - 1000),
                                       'end': str(time.time_ns() // 1000), 'limit': '20'})
        results = request(jaeger + '/api/traces?' + query)['data']
        require(any(item['traceID'] == trace for item in results), 'Jaeger trace search failed')
    retry(search_check)
    def log_check():
        query = urllib.parse.urlencode({'query': '{app="local-verification",run="' + marker['id'] + '"}',
                                       'start': str(marker['timestamp'] - 1000000), 'end': str(time.time_ns())})
        body = request(loki + '/loki/api/v1/query_range?' + query)
        require(body.get('status') == 'success' and bool(body['data']['result']), 'Loki log query failed')
    retry(log_check)
    grafana = base + str(e.ports['grafana'])
    auth = 'Basic ' + base64.b64encode(('admin:' + e.credentials['grafana']).encode()).decode()
    headers = {'Authorization': auth}
    sources = request(grafana + '/api/datasources', headers=headers)
    require({row['uid'] for row in sources} >= {'jaeger', 'loki'}, 'Grafana data sources are missing')
    for uid in ('jaeger', 'loki'):
        body = request(grafana + '/api/datasources/uid/' + uid + '/health', headers=headers)
        require(body['status'] == 'OK', 'Grafana data source is not healthy: ' + uid)
    queries = [
        ({'queryType': '', 'query': trace},
         {'traceID': trace, 'spanID': marker['span'], 'operationName': marker['id'], 'serviceName': 'local-verification'}),
        ({'queryType': 'search', 'service': 'local-verification', 'operation': marker['id'], 'limit': 20},
         {'traceID': trace, 'traceName': 'local-verification: ' + marker['id']}),
    ]
    for query, expected in queries:
        result = request(grafana + '/api/ds/query', 'POST', headers=headers, json_body={
            'from': str(marker['timestamp'] // 1000000 - 60000),
            'to': str(marker['timestamp'] // 1000000 + 60000),
            'queries': [{'refId': 'A', 'datasource': {'type': 'jaeger', 'uid': 'jaeger'},
                         'maxDataPoints': 100, 'intervalMs': 1000, **query}]})['results']['A']
        require(result.get('status') == 200 and not result.get('error'), 'Grafana trace query failed')
        found = False
        for frame in result.get('frames', []):
            names = [field['name'] for field in frame['schema']['fields']]
            for values in zip(*frame['data']['values']):
                row = dict(zip(names, values))
                found |= all(row.get(key) == value for key, value in expected.items())
        require(found, 'Grafana query did not return the expected trace')
    try:
        dashboard = request(grafana + '/api/dashboards/uid/' + marker['id'], headers=headers)
    except HttpFailure as error:
        if error.status != 404 or marker['existing']:
            raise
        request(grafana + '/api/dashboards/db', 'POST', headers=headers,
                json_body={'dashboard': {'uid': marker['id'], 'title': 'Local verification ' + marker['id'],
                                         'schemaVersion': 39, 'panels': []}, 'overwrite': False})
        dashboard = request(grafana + '/api/dashboards/uid/' + marker['id'], headers=headers)
    require(dashboard['dashboard']['uid'] == marker['id'], 'Grafana persistent dashboard missing')


def mqadmin(e, *args):
    return e.compose('exec', '-T', 'rocketmq-broker', 'java', '-Xms32m', '-Xmx128m',
                     '-cp', '../conf:../lib/*', 'org.apache.rocketmq.tools.command.MQAdminStartup',
                     *args, phase='RocketMQ administration')


def message_client(e):
    client = e.state / 'mq-client'
    expected_image = e.image('rocketmq')
    completion = client / 'complete.json'
    if completion.exists():
        manifest = json.loads(completion.read_text())
        require(manifest['image'] == expected_image, 'Message client belongs to another image')
        require(all((client / name).is_file() and hashlib.sha256((client / name).read_bytes()).hexdigest() == digest
                    for name, digest in manifest['files'].items()), 'Copied message client files changed')
    else:
        if client.exists():
            client.rename(e.state / ('mq-client-incomplete-' + str(time.time_ns())))
        with tempfile.TemporaryDirectory(prefix='mq-client-copy-', dir=e.state) as temporary:
            staging = Path(temporary) / 'client'
            staging.mkdir()
            e.docker('cp', e.args.project + '-rocketmq-broker-1:' + e.image_home('rocketmq') + '/rocketmq-5.3.2/lib',
                     str(staging / 'lib'), phase='copy official message client')
            for component in ('client', 'common', 'remoting'):
                require(bool(list((staging / 'lib').glob('rocketmq-' + component + '-*.jar'))), 'Message client copy is incomplete')
            files = {str(p.relative_to(staging)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in (staging / 'lib').glob('*.jar')}
            protected_write(staging / 'complete.json', json.dumps({'image': expected_image, 'files': files}) + '\n')
            staging.rename(client)
    return client


def verify_mq(e, marker):
    topic = 'local-verification-' + marker['id']
    group = topic + '-consumer'
    broker = 'localhost:' + str(e.ports['mq_broker'])
    mqadmin(e, 'updateTopic', '-b', broker, '-t', topic, '-r', '1', '-w', '1')
    mqadmin(e, 'updateSubGroup', '-b', broker, '-g', group)
    client = message_client(e)
    e.run(['javac', '-cp', str(client / 'lib/*'), '-d', str(client),
           str(e.source / 'init/MessageProbe.java')], phase='compile message probe')
    e.run(['java', '--sun-misc-unsafe-memory-access=allow', '--enable-native-access=ALL-UNNAMED', '-cp', str(client) + ':' + str(client / 'lib/*'), 'MessageProbe',
           e.args.bind + ':' + str(e.ports['mq_nameserver']), topic, marker['id'], group, 'read' if marker['existing'] else 'write'],
          phase='host message round trip', timeout=120)
    e.run(['java', '--sun-misc-unsafe-memory-access=allow', '--enable-native-access=ALL-UNNAMED', '-cp', str(client) + ':' + str(client / 'lib/*'), 'MessageProbe',
           e.args.bind + ':' + str(e.ports['mq_proxy_remoting']), topic, marker['id'], group, 'proxy'],
          phase='Proxy remoting request', timeout=45)
    e.run(['java', '--sun-misc-unsafe-memory-access=allow', '--enable-native-access=ALL-UNNAMED', '-cp', str(client) + ':' + str(client / 'lib/*'), 'MessageProbe',
           e.args.bind + ':' + str(e.ports['mq_proxy_grpc']), topic, marker['id'], group, 'grpc'],
          phase='Proxy gRPC request', timeout=45)


def verify_scheduler(e):
    count = e.sql("SELECT COUNT(*) FROM xxl_job.xxl_job_info WHERE executor_handler='localVerification' AND trigger_status=0;")
    require(int(count) == len(e.args.slots), 'Scheduler seed count or stopped state differs')
    require(e.sql('SELECT COUNT(*) FROM xxl_job.xxl_job_user;') == '1', 'Unexpected scheduler users')
    body = request('http://' + e.args.bind + ':' + str(e.ports['xxl']) + '/auth/doLogin', 'POST',
                   {'userName': 'admin', 'password': e.credentials['xxl']})
    require(body.get('code') == 200, 'Scheduler administrator login failed')


def verify(e):
    rows = e.status(emit=False)
    verify_limits(e, rows)
    path = e.state / 'verification-data.json'
    if e.args.new_verification_cycle and path.exists():
        archive = e.state / ('verification-data-' + str(time.time_ns()) + '.json')
        path.rename(archive)
    if path.exists():
        marker = json.loads(path.read_text())
        marker['existing'] = marker.get('complete', False)
    else:
        marker = {'id': secrets.token_hex(12), 'trace': secrets.token_hex(16),
                  'span': secrets.token_hex(8), 'timestamp': time.time_ns(), 'existing': False}
        protected_write(path, json.dumps(marker, indent=2) + '\n')
    passed = []
    for name, operation in [('nacos', verify_nacos), ('mysql', verify_mysql), ('redis', verify_redis),
                             ('observability', verify_observability), ('rocketmq', verify_mq)]:
        operation(e, marker)
        passed.append(name)
        print(name + ': verified', flush=True)
    verify_scheduler(e)
    passed.append('xxl-job-admin')
    marker['complete'] = True
    protected_write(path, json.dumps(marker, indent=2) + '\n')
    report = {'created_at': time.time(), 'project': e.args.project, 'checks': passed,
              'persistent_readback': marker['existing'], 'source_digest': e.source_digest(),
              'source_commits': {'framework': os.environ.get('MIDDLEWARE_FRAMEWORK_SHA'),
                                 'service': os.environ.get('MIDDLEWARE_SERVICE_SHA')}, 'health': rows, 'images': e.images,
              'source_sha256': {str(p.relative_to(e.source)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in e.source.rglob('*') if p.is_file() and '.local' not in p.parts
                                and '__pycache__' not in p.parts}, 'verification_id': marker['id']}
    protected_write(e.state / ('verification-' + str(time.time_ns()) + '.json'), json.dumps(report, indent=2) + '\n')
    print('Middleware verification passed')
