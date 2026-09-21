#!/usr/bin/env python3
"""Manage an isolated local middleware environment without deleting its data."""
import argparse
import base64
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import socket
import subprocess
import sys
import time
import uuid

BASE_PORTS = dict(nacos_console=8080, nacos_http=8848, nacos_grpc=9848,
                  mq_nameserver=9876, mq_broker=10911, mq_vip=10909, mq_ha=10912,
                  mq_proxy_remoting=8081, mq_proxy_grpc=8082, mysql=3306, redis=6379,
                  jaeger_ui=16686, otlp_grpc=4317, otlp_http=4318, loki=3100,
                  grafana=3000, xxl=8083)
SERVICES = ('nacos', 'rocketmq-nameserver', 'rocketmq-broker', 'mysql', 'redis',
            'jaeger', 'loki', 'grafana', 'xxl-job-admin')
OWNER_LABEL = 'io.mars.middleware.owner'


class Failure(RuntimeError):
    pass


def protected_write(path, data, mode=0o600):
    path = Path(path)
    if path.is_symlink():
        raise Failure('Refusing to write through a symbolic link')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    with os.fdopen(fd, 'w') as stream:
        os.fchmod(stream.fileno(), mode)
        stream.write(data)


def validate(project, offset, bind, namespace, prefix, slots):
    if not re.fullmatch(r'mars-lab(?:-[a-z0-9]+(?:-[a-z0-9]+)*)?', project):
        raise Failure('Project must be mars-lab or start with mars-lab-')
    if not isinstance(offset, int) or offset < 0:
        raise Failure('Port offset must be a nonnegative integer')
    ports = {key: value + offset for key, value in BASE_PORTS.items()}
    if len(set(ports.values())) != len(ports) or not all(1024 <= p <= 65535 for p in ports.values()):
        raise Failure('Published ports must be distinct and in range 1024..65535')
    try:
        address = ipaddress.ip_address(bind)
    except ValueError:
        raise Failure('Bind address must be a literal IPv4 address') from None
    if address.version != 4:
        raise Failure('This environment requires IPv4')
    for value in (namespace, prefix):
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,62}', value):
            raise Failure('Invalid namespace ID or prefix')
    if not slots or len(set(slots)) != len(slots) or any(n not in range(7) for n in slots):
        raise Failure('Environment numbers must be unique integers from 0 to 6')
    namespaces = [namespace if n == 0 else prefix + str(n) for n in slots]
    if len(set(namespaces)) != len(namespaces):
        raise Failure('Namespace IDs collide')
    return ports


class Environment:
    def __init__(self, args):
        self.args = args
        self.source = Path(__file__).resolve().parent
        self.state = Path(args.state_dir or self.source / '.local' / args.project).absolute()
        if self.state.is_symlink() or self.state.resolve() != self.state:
            raise Failure('State directory must not contain symbolic links')
        self.project_dir = Path(args.project_directory or self.source.parent).resolve()
        self.compose_file = Path(args.compose_file or self.source / 'docker-compose.yml').resolve()
        if self.compose_file.parent != self.source:
            raise Failure('Compose and Python sources must belong to the same candidate')
        self.ports = validate(args.project, args.offset, args.bind, args.base_namespace,
                              args.namespace_prefix, args.slots)
        self.binding = dict(project=args.project, offset=args.offset, bind=args.bind,
                            advertise=args.advertise, platform=args.platform, namespace=args.base_namespace, prefix=args.namespace_prefix,
                            slots=args.slots, state=str(self.state), project_directory=str(self.project_dir))
        self.images = json.loads((self.source / 'images.lock.json').read_text())['images']
        for meta in self.images.values():
            if not re.fullmatch(r'sha256:[0-9a-f]{64}', meta['digest']):
                raise Failure('Invalid image digest')
        if ipaddress.ip_address(args.advertise).version != 4:
            raise Failure("Broker advertised address must be IPv4")
        self.owner = None
        self.credentials = None

    @contextmanager
    def locked(self):
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.state.chmod(0o700)
        lock = self.state / '.lock'
        if lock.is_symlink():
            raise Failure('Invalid lock path')
        with lock.open('a') as stream:
            os.fchmod(stream.fileno(), 0o600)
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise Failure('Another operation owns this state directory') from None
            yield

    def load(self, create=False):
        record = self.state / 'binding.json'
        if record.exists():
            saved = json.loads(record.read_text())
            if saved['binding'] != self.binding:
                raise Failure('Existing state is bound to different parameters')
            self.owner = saved['owner']
            credentials = self.state / 'credentials.json'
            if not credentials.exists():
                raise Failure('Credentials are missing; restore the protected state directory')
            self.credentials = json.loads(credentials.read_text())
        elif create:
            # Do not adopt resources belonging to an earlier or foreign state directory.
            self.check_ownership()
            self.owner = uuid.uuid4().hex
            self.credentials = {key: secrets.token_hex(24) for key in
                                ('nacos', 'identity_key', 'identity_value', 'mysql_root',
                                 'redis', 'grafana', 'xxl', 'xxl_db', 'xxl_access')}
            self.credentials['xxl'] = secrets.token_hex(10)
            self.credentials['nacos_auth'] = base64.b64encode(secrets.token_bytes(48)).decode()
            self.credentials['databases'] = {
                f'{service}{"_s" + str(n) if n else ""}': secrets.token_hex(24)
                for service in ('auth', 'upms', 'product', 'order', 'notice') for n in self.args.slots}
            protected_write(self.state / 'credentials.json', json.dumps(self.credentials, indent=2) + '\n')
            protected_write(record, json.dumps({'binding': self.binding, 'owner': self.owner}, indent=2) + '\n')
        else:
            raise Failure('No initialized state directory')
        self.check_ownership()

    def run(self, command, *, data=None, check=True, phase='command', env=None, timeout=180):
        try:
            result = subprocess.run(command, cwd=self.project_dir, input=data, capture_output=True,
                                    text=True, env=env, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise Failure(phase + ' timed out; existing data has been preserved') from None
        if result.returncode and check:
            protected_write(self.state / 'last-error.log', result.stdout + result.stderr)
            raise Failure(phase + ' failed; inspect protected last-error.log')
        return result

    def docker(self, *args, **kwargs):
        return self.run(['docker', *args], **kwargs)

    def check_ownership(self):
        for kind, cmd in (('container', ['ps', '-aq']), ('volume', ['volume', 'ls', '-q']),
                          ('network', ['network', 'ls', '-q'])):
            found = self.docker(*cmd, '--filter', 'label=com.docker.compose.project=' + self.args.project).stdout.split()
            if not found:
                continue
            inspected = json.loads(self.docker(kind, 'inspect', *found).stdout)
            for resource in inspected:
                labels = (resource.get('Config') or {}).get('Labels', resource.get('Labels')) or {}
                if not self.owner or labels.get(OWNER_LABEL) != self.owner:
                    raise Failure('Project has resources belonging to another state directory')
        definition = json.loads(self.compose_file.read_text())
        names = [('volume', self.args.project + '_' + name) for name in definition['volumes']]
        names.append(('network', self.args.project + '_default'))
        for kind, name in names:
            found = self.docker(kind, 'inspect', name, check=False)
            if found.returncode == 0:
                labels = json.loads(found.stdout)[0].get('Labels') or {}
                if (not self.owner or labels.get(OWNER_LABEL) != self.owner
                        or labels.get('com.docker.compose.project') != self.args.project):
                    raise Failure('A declared resource name belongs to another environment')
        for service in SERVICES:
            found = self.docker('container', 'inspect', self.args.project + '-' + service + '-1', check=False)
            if found.returncode == 0:
                labels = json.loads(found.stdout)[0]['Config'].get('Labels') or {}
                if not self.owner or labels.get(OWNER_LABEL) != self.owner:
                    raise Failure('A container name is already owned by another environment')

    def image(self, key):
        meta = self.images[key]
        return meta['tag'].rsplit(':', 1)[0] + '@' + meta['digest']

    def image_home(self, key):
        metadata = json.loads(self.docker('image', 'inspect', self.image(key)).stdout)[0]['Config']
        if key == 'nacos':
            return metadata['WorkingDir']
        return str(Path(metadata['WorkingDir']).parent.parent)

    def source_digest(self):
        hashes = {str(p.relative_to(self.source)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in self.source.rglob('*') if p.is_file() and '.local' not in p.parts
                  and '__pycache__' not in p.parts}
        return hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()

    def compose_env(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith(('COMPOSE_', 'DOCKER_DEFAULT_PLATFORM'))}
        env.update(MIDDLEWARE_OWNER=self.owner, MIDDLEWARE_STATE=str(self.state),
                   MIDDLEWARE_SOURCE=str(self.source), MIDDLEWARE_BIND_ADDRESS=self.args.bind)
        env.update({'PORT_' + key.upper(): str(value) for key, value in self.ports.items()})
        env.update({key.upper().replace('-', '_') + '_IMAGE': self.image(key) for key in self.images})
        env['MIDDLEWARE_SOURCE_DIGEST'] = self.source_digest()
        env['NACOS_IMAGE_HOME'] = self.image_home('nacos')
        env['ROCKETMQ_IMAGE_HOME'] = self.image_home('rocketmq')
        env['JAEGER_LOCAL_IMAGE'] = 'mars-lab-jaeger:' + self.jaeger_build_id()
        env.update(NACOS_AUTH_TOKEN=self.credentials['nacos_auth'],
                   NACOS_AUTH_IDENTITY_KEY=self.credentials['identity_key'],
                   NACOS_AUTH_IDENTITY_VALUE=self.credentials['identity_value'],
                   GRAFANA_PASSWORD=self.credentials['grafana'])
        return env

    def jaeger_build_id(self):
        data = (self.source / 'images/jaeger/Dockerfile').read_bytes()
        data += (self.image('jaeger') + self.image('busybox')).encode()
        return hashlib.sha256(data).hexdigest()[:24]

    def compose(self, *args, **kwargs):
        return self.run(['docker', 'compose', '--project-name', self.args.project,
                         '--project-directory', str(self.project_dir), '--env-file', os.devnull,
                         '-f', str(self.compose_file), *args], env=self.compose_env(), **kwargs)

    def preflight(self):
        active = json.loads(self.docker('ps', '--filter', 'label=com.docker.compose.project=' + self.args.project,
                                       '--format', '{{json .}}').stdout.replace('\n', ',').rstrip(',').join(['[', ']']))
        published = set()
        for container in active:
            meta = json.loads(self.docker('inspect', container['ID']).stdout)[0]
            for values in (meta['NetworkSettings']['Ports'] or {}).values():
                for value in values or []:
                    published.add(int(value['HostPort']))
        for port in self.ports.values():
            if port in published:
                continue
            with socket.socket() as probe:
                try:
                    probe.bind((self.args.bind, port))
                except OSError:
                    raise Failure('Published port is already occupied: ' + str(port)) from None

    def render(self):
        c = self.credentials
        protected_write(self.state / 'mysql-root', c['mysql_root'])
        protected_write(self.state / 'mysql.cnf', '[client]\nuser=root\npassword=' + c['mysql_root'] + '\n')
        protected_write(self.state / 'redis-password', c['redis'])
        protected_write(self.state / 'redis.conf', 'bind 0.0.0.0\nprotected-mode yes\nport 6379\n'
                        'appendonly yes\nappendfsync everysec\nsave 60 1\nmaxmemory 256mb\n'
                        'maxmemory-policy noeviction\nrequirepass ' + c['redis'] + '\n')
        broker = f'brokerClusterName=LocalCluster\nbrokerName=local-broker\nbrokerId=0\n'
        broker += f'brokerIP1={self.args.advertise}\nlistenPort={self.ports["mq_broker"]}\n'
        broker += f'haListenPort={self.ports["mq_ha"]}\n'
        broker += 'namesrvAddr=rocketmq-nameserver:9876\nautoCreateTopicEnable=false\nautoCreateSubscriptionGroup=false\n'
        broker += f'storePathRootDir={self.image_home("rocketmq")}/store\nfileReservedTime=48\ndeleteWhen=04\n'
        broker += 'brokerRole=ASYNC_MASTER\nflushDiskType=SYNC_FLUSH\nmappedFileSizeCommitLog=67108864\n'
        protected_write(self.state / 'broker.conf', broker, 0o644)
        protected_write(self.state / 'xxl.properties',
                        'spring.datasource.url=jdbc:mysql://mysql:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC\n'
                        'spring.datasource.username=xxl_job\nspring.datasource.password=' + c['xxl_db'] + '\n'
                        'spring.datasource.hikari.initialization-fail-timeout=-1\n'
                        'spring.datasource.hikari.minimum-idle=1\nspring.datasource.hikari.maximum-pool-size=5\n'
                        'xxl.job.accessToken=' + c['xxl_access'] + '\nxxl.job.logretentiondays=7\n'
                        'xxl.job.triggerpool.fast.max=10\nxxl.job.triggerpool.slow.max=10\n')

    def prepare_images(self):
        for key in self.images:
            platform = 'linux/amd64' if key == 'xxl-job-admin' else self.args.platform
            installed = self.docker('image', 'inspect', self.image(key), check=False)
            if installed.returncode == 0:
                metadata = json.loads(installed.stdout)[0]
                if metadata['Os'] + '/' + metadata['Architecture'] == platform:
                    continue
            self.docker('pull', '--platform', platform, self.image(key), phase='pull ' + key, timeout=900)
        self.docker('build', '--platform', self.args.platform, '-t', 'mars-lab-jaeger:' + self.jaeger_build_id(),
                    '--build-arg', 'BUSYBOX_IMAGE=' + self.image('busybox'), '--build-arg',
                    'JAEGER_IMAGE=' + self.image('jaeger'), str(self.source / 'images/jaeger'),
                    phase='build Jaeger probe', timeout=300)

    def prepare_volumes(self):
        # Compose creates volumes and labels; initialize only the new data directories.
        self.compose('create', 'jaeger', 'loki', 'rocketmq-broker', 'rocketmq-nameserver', phase='create storage')
        self.docker('run', '--rm', '--name', self.args.project + '-prepare-probe',
                    '--restart', 'no', '--cpus', '.5', '--memory', '512m', '--memory-swap', '512m',
                    '--pids-limit', '128', '--log-driver', 'json-file', '--log-opt', 'max-size=10m',
                    '--log-opt', 'max-file=3', '--label', OWNER_LABEL + '=' + self.owner,
                    '-v', self.args.project + '_health-probe:/probe', self.image('busybox'),
                    'sh', '-c', 'cp /bin/busybox /probe/busybox && chmod 755 /probe/busybox',
                    phase='prepare health probe')
        for service, uid in {'jaeger-data': 10001, 'loki-data': 10001, 'broker-data': 3000,
                             'broker-logs': 3000, 'nameserver-logs': 3000}.items():
            volume = self.args.project + '_' + service
            self.docker('run', '--rm', '--name', self.args.project + '-prepare-' + service,
                        '--restart', 'no', '--cpus', '.5', '--memory', '512m', '--memory-swap', '512m',
                        '--pids-limit', '128', '--log-driver', 'json-file', '--log-opt', 'max-size=10m',
                        '--log-opt', 'max-file=3', '--label', OWNER_LABEL + '=' + self.owner,
                        '-v', volume + ':/data', self.image('busybox'), 'chown', str(uid) + ':' + str(uid), '/data',
                        phase='prepare ' + service + ' storage')

    def wait(self, services, timeout=420):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            result = self.status(services, emit=False, require=False)
            if all(item['healthy'] for item in result):
                return
            time.sleep(3)
        raise Failure('Health timeout: ' + ', '.join(item['service'] for item in result if not item['healthy']))

    def status(self, services=SERVICES, emit=True, require=True):
        rows = []
        for service in services:
            result = self.docker('inspect', self.args.project + '-' + service + '-1', check=False)
            if result.returncode:
                rows.append(dict(service=service, healthy=False, status='missing'))
                continue
            info = json.loads(result.stdout)[0]
            state, host = info['State'], info['HostConfig']
            rows.append(dict(service=service, healthy=state['Running'] and not state['Restarting']
                             and state.get('Health', {}).get('Status') == 'healthy' and not state['OOMKilled'],
                             status=state.get('Health', {}).get('Status', state['Status']),
                             restarts=info['RestartCount'], oom=state['OOMKilled'],
                             memory=host['Memory'], swap=host['MemorySwap'], cpus=host['NanoCpus'],
                             pids=host['PidsLimit'], restart=host['RestartPolicy']['Name'],
                             log=host['LogConfig'], image=info['Image']))
        if emit:
            print(json.dumps(rows, indent=2))
        if require and not all(row['healthy'] for row in rows):
            raise Failure('Some middleware services are not healthy')
        return rows

    def sql(self, query):
        return self.compose('exec', '-T', 'mysql', 'mysql', '--defaults-extra-file=/run/local/mysql.cnf',
                            '--batch', '--skip-column-names', data=query, phase='MySQL query').stdout.strip()

    def up(self):
        self.preflight()
        self.prepare_images()
        self.render()
        self.prepare_volumes()
        self.compose('up', '-d', 'mysql', 'redis', 'nacos', 'rocketmq-nameserver', 'jaeger', 'loki',
                     phase='start dependencies', timeout=240)
        self.wait(('mysql', 'redis', 'nacos', 'rocketmq-nameserver', 'jaeger', 'loki'))
        from middleware_init import initialize
        initialize(self)
        self.compose('up', '-d', phase='start middleware', timeout=240)
        self.wait(SERVICES)
        from middleware_verify import verify
        verify(self)

    def export_env(self):
        n = self.args.slot
        if n not in self.args.slots:
            raise Failure('Environment number was not initialized')
        values = dict(NACOS_SERVER_ADDR=f'{self.args.bind}:{self.ports["nacos_http"]}',
                      NACOS_NAMESPACE_ID=self.args.base_namespace if n == 0 else self.args.namespace_prefix + str(n),
                      NACOS_USERNAME='nacos', NACOS_PASSWORD=self.credentials['nacos'],
                      SPRING_DATA_REDIS_HOST=self.args.bind, SPRING_DATA_REDIS_PORT=str(self.ports['redis']),
                      SPRING_DATA_REDIS_PASSWORD=self.credentials['redis'], SPRING_DATA_REDIS_DATABASE=str(n),
                      MARS_DB_SUFFIX='' if n == 0 else '_s' + str(n), MARS_MQ_PREFIX='' if n == 0 else 's' + str(n) + '-',
                      ROCKETMQ_NAME_SERVER=f'{self.args.bind}:{self.ports["mq_nameserver"]}')
        output = Path(self.args.output or self.state / ('environment-' + str(n) + '.env'))
        if output.exists():
            raise Failure('Environment output already exists; choose a new path')
        protected_write(output, ''.join(k + '=' + shlex.quote(v) + '\n' for k, v in values.items()))
        print('Connection variables written to protected file: ' + str(output))


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['up', 'status', 'verify', 'down', 'export-env'])
    p.add_argument('--project', default=os.environ.get('COMPOSE_PROJECT_NAME', 'mars-lab'))
    p.add_argument('--offset', type=int, default=int(os.environ.get('MIDDLEWARE_PORT_OFFSET', '20000')))
    p.add_argument('--bind', default=os.environ.get('MIDDLEWARE_BIND_ADDRESS', '127.0.0.1'))
    p.add_argument('--advertise', default='127.0.0.1')
    p.add_argument('--base-namespace', default=os.environ.get('NACOS_BASE_NAMESPACE_ID', 'mars-local'))
    p.add_argument('--namespace-prefix', default=os.environ.get('NACOS_NAMESPACE_PREFIX', 'mars-slot-'))
    p.add_argument('--slots', type=lambda s: [int(n) for n in s.split(',')], default=list(range(7)))
    p.add_argument('--state-dir')
    p.add_argument('--compose-file')
    p.add_argument('--project-directory')
    p.add_argument('--platform', choices=['linux/arm64', 'linux/amd64'], default='linux/arm64' if os.uname().machine in ('arm64', 'aarch64') else 'linux/amd64')
    p.add_argument('--slot', type=int, default=0)
    p.add_argument('--output')
    p.add_argument('--new-verification-cycle', action='store_true', help='Archive completed evidence and create new probe data')
    return p


def main():
    args = parser().parse_args()
    environment = Environment(args)
    with environment.locked():
        environment.load(create=args.action == 'up')
        if args.action == 'up':
            environment.up()
        elif args.action == 'status':
            environment.status()
        elif args.action == 'verify':
            from middleware_verify import verify
            verify(environment)
        elif args.action == 'down':
            environment.compose('down', '--timeout', '60', phase='stop project', timeout=180)
            print('Project stopped; all data volumes retained')
        else:
            environment.export_env()


if __name__ == '__main__':
    sys.modules['middleware'] = sys.modules[__name__]
    try:
        main()
    except (Failure, ValueError, OSError, KeyError) as error:
        print('Middleware operation failed: ' + str(error), file=sys.stderr)
        raise SystemExit(1) from None
