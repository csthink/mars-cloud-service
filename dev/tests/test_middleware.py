import argparse
from contextlib import contextmanager
import importlib.util
import json
from pathlib import Path
import socket
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
import threading
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import middleware as m
import middleware_init as init
import middleware_verify as verify


class Parameters(unittest.TestCase):
    def valid(self, **values):
        args = dict(project='mars-lab-check', offset=30000, bind='127.0.0.1',
                    namespace='mars-local', prefix='mars-slot-', slots=list(range(7)))
        args.update(values)
        return m.validate(**args)

    def test_two_sets_have_distinct_ports(self):
        self.assertFalse(set(self.valid(offset=20000).values()) & set(self.valid().values()))

    def test_reject_ambiguous_parameters(self):
        for values in ({'project': 'another-project'}, {'offset': -1}, {'offset': 60000},
                       {'slots': [1, 1]}, {'slots': [7]}, {'slots': []},
                       {'namespace': 'mars-slot-1'}, {'prefix': 'x\n'}, {'bind': 'localhost'}):
            with self.subTest(values=values), self.assertRaises(m.Failure):
                self.valid(**values)

    def test_private_file_permissions_and_symlinks(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'credentials'
            m.protected_write(path, 'example')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            link = Path(root) / 'link'
            link.symlink_to(path)
            with self.assertRaises(m.Failure):
                m.protected_write(link, 'replacement')
            self.assertEqual(path.read_text(), 'example')


class State(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        args = m.parser().parse_args(['up', '--state-dir', str(Path(self.temp.name).resolve() / 'state')])
        self.environment = m.Environment(args)
        self.environment.check_ownership = Mock()

    def tearDown(self):
        self.temp.cleanup()

    def test_credentials_survive_reinitialization(self):
        e = self.environment
        with e.locked():
            e.load(create=True)
            old = e.credentials.copy()
            e.load(create=True)
            self.assertEqual(old, e.credentials)
            self.assertEqual((e.state / 'credentials.json').stat().st_mode & 0o777, 0o600)

    def test_parameter_change_and_missing_credentials_refused(self):
        e = self.environment
        with e.locked():
            e.load(create=True)
            e.binding['offset'] = 123
            with self.assertRaises(m.Failure):
                e.load()
            e.binding['offset'] = 20000
            (e.state / 'credentials.json').unlink()
            with self.assertRaises(m.Failure):
                e.load(create=True)

    def test_foreign_resource_prevents_initialization(self):
        e = self.environment
        e.check_ownership.side_effect = m.Failure('foreign')
        with e.locked(), self.assertRaises(m.Failure):
            e.load(create=True)
        self.assertFalse((e.state / 'credentials.json').exists())

    def test_state_lock_rejects_concurrent_operation(self):
        e = self.environment
        with e.locked(), self.assertRaises(m.Failure):
            with e.locked():
                pass

    def test_export_never_overwrites_existing_environment(self):
        e = self.environment
        with e.locked():
            e.load(create=True)
            e.export_env()
            output = e.state / 'environment-0.env'
            old = output.read_bytes()
            with self.assertRaises(m.Failure):
                e.export_env()
            self.assertEqual(output.read_bytes(), old)

    def test_other_projects_are_rejected_by_label(self):
        e = self.environment
        e.owner = 'expected'
        e.docker = Mock(side_effect=[Mock(stdout='container-id'), Mock(stdout=json.dumps([
            {'Config': {'Labels': {m.OWNER_LABEL: 'foreign'}}}]))])
        with self.assertRaises(m.Failure):
            m.Environment.check_ownership(e)

    def test_occupied_port_refused_before_start(self):
        e = self.environment
        e.docker = Mock(return_value=Mock(stdout=''))
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', 0))
            occupied.listen()
            e.ports = {'test': occupied.getsockname()[1]}
            with self.assertRaises(m.Failure):
                e.preflight()


class Initialization(unittest.TestCase):
    def test_applied_migration_change_is_not_executed(self):
        e = Mock()
        e.credentials = {'databases': {}}
        e.source = Path(__file__).resolve().parents[1]
        e.sql.side_effect = ['', 'different-digest\t1']
        with self.assertRaises(m.Failure):
            init.initialize_mysql(e)
        self.assertEqual(e.sql.call_count, 2)

    def test_nacos_conflict_does_not_overwrite_user_content(self):
        e = Mock()
        e.args.slots = [0]
        e.args.base_namespace = 'base'
        api = Mock()
        api.call.side_effect = [[{'namespace': 'base'}], {'content': 'user-content', 'type': 'yaml'}]
        api.items.return_value = [{'groupName': 'COMMON', 'dataId': 'shared-common.yaml'}]
        with patch.object(init, 'Nacos', return_value=api), self.assertRaises(m.Failure):
            init.initialize_nacos(e)
        self.assertTrue(all(call.args[0] == 'GET' for call in api.call.call_args_list))


class MessageClient(unittest.TestCase):
    def test_interrupted_copy_can_retry_and_detects_later_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            e = Mock()
            e.state = Path(directory)
            e.args.project = 'mars-lab'
            e.image.return_value = 'example@sha256:fixed'
            e.image_home.return_value = '/image'

            def copy(*args, **kwargs):
                destination = Path(args[2])
                destination.mkdir()
                for component in ('client', 'common', 'remoting'):
                    (destination / ('rocketmq-' + component + '-5.3.2.jar')).write_bytes(b'complete')

            def interrupt(*args, **kwargs):
                copy(*args, **kwargs)
                raise m.Failure('copy interrupted')

            e.docker.side_effect = interrupt
            with self.assertRaises(m.Failure):
                verify.message_client(e)
            self.assertFalse((e.state / 'mq-client').exists())
            self.assertFalse(list(e.state.glob('mq-client-copy-*')))
            e.docker.side_effect = copy
            client = verify.message_client(e)
            self.assertTrue((client / 'complete.json').is_file())
            e.docker.reset_mock()
            self.assertEqual(verify.message_client(e), client)
            e.docker.assert_not_called()
            (client / 'lib/rocketmq-client-5.3.2.jar').write_bytes(b'truncated')
            with self.assertRaises(m.Failure):
                verify.message_client(e)

    def test_incomplete_legacy_copy_is_preserved_and_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            e = Mock()
            e.state = Path(directory)
            e.args.project = 'mars-lab'
            e.image.return_value = 'example@sha256:fixed'
            e.image_home.return_value = '/image'
            old = e.state / 'mq-client'
            old.mkdir()
            (old / 'partial').write_text('preserve')

            def copy(*args, **kwargs):
                destination = Path(args[2])
                destination.mkdir()
                for component in ('client', 'common', 'remoting'):
                    (destination / ('rocketmq-' + component + '-5.3.2.jar')).write_bytes(b'complete')

            e.docker.side_effect = copy
            self.assertTrue((verify.message_client(e) / 'complete.json').exists())
            backups = list(e.state.glob('mq-client-incomplete-*'))
            self.assertEqual(len(backups), 1)
            self.assertEqual((backups[0] / 'partial').read_text(), 'preserve')


class SharedImage(unittest.TestCase):
    def test_platform_is_part_of_derived_image_identity(self):
        e = m.Environment(m.parser().parse_args(['up']))
        e.args.platform = 'linux/arm64'
        arm = e.jaeger_build_id()
        e.args.platform = 'linux/amd64'
        self.assertNotEqual(arm, e.jaeger_build_id())

    def test_concurrent_environments_build_once_then_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            environments = [m.Environment(m.parser().parse_args(['up'])) for _ in range(2)]
            image = None
            builds = []
            barrier = threading.Barrier(2)
            def docker(*args, **kwargs):
                nonlocal image
                if args[0] == 'build':
                    builds.append(args)
                    time.sleep(0.1)
                    image = {'Id': 'sha256:' + 'a' * 64, 'Os': 'linux', 'Architecture': environments[0].args.platform.split('/')[1],
                             'Config': {'Labels': {'io.mars.middleware.jaeger-build': environments[0].jaeger_build_id()}}}
                    return Mock(returncode=0, stdout='')
                return Mock(returncode=0 if image else 1, stdout=json.dumps([image]) if image else '')
            def prepare(e):
                e.docker = docker
                barrier.wait(timeout=5)
                e.prepare_jaeger()
            with patch.object(m, 'IMAGE_LOCK_ROOT', Path(directory)), ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(prepare, environments))
                self.assertEqual(len(builds), 1)
                environments[0].prepare_jaeger()
                self.assertEqual(len(builds), 1)
                image['Config']['Labels'] = {}
                with self.assertRaises(m.Failure):
                    environments[0].prepare_jaeger()
                self.assertEqual(len(builds), 1)

    def test_failed_build_releases_lock_and_can_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            e = m.Environment(m.parser().parse_args(['up']))
            metadata = {'Id': 'sha256:' + 'b' * 64, 'Os': 'linux', 'Architecture': e.args.platform.split('/')[1],
                        'Config': {'Labels': {'io.mars.middleware.jaeger-build': e.jaeger_build_id()}}}
            e.docker = Mock(side_effect=[Mock(returncode=1), m.Failure('build failed'),
                                        Mock(returncode=1), Mock(returncode=0),
                                        Mock(returncode=0, stdout=json.dumps([metadata]))])
            with patch.object(m, 'IMAGE_LOCK_ROOT', Path(directory)):
                with self.assertRaises(m.Failure):
                    e.prepare_jaeger()
                e.prepare_jaeger()
            self.assertEqual(sum(call.args[0] == 'build' for call in e.docker.call_args_list), 2)

    def test_shared_build_lock_refuses_symbolic_link(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'target'
            target.write_text('preserve')
            lock = Path(directory) / ('mars-middleware-image-' + str(m.os.getuid()) + '-test.lock')
            lock.symlink_to(target)
            with patch.object(m, 'IMAGE_LOCK_ROOT', Path(directory)), self.assertRaises(OSError):
                with m.image_build_lock('test'):
                    pass
            self.assertEqual(target.read_text(), 'preserve')


if __name__ == '__main__':
    unittest.main()
