import argparse
from contextlib import contextmanager
import importlib.util
import json
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import middleware as m
import middleware_init as init


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


if __name__ == '__main__':
    unittest.main()
