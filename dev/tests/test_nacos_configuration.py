import copy
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import middleware as m
import middleware_init as init
import middleware_nacos_config as config


def document(content='flag: preserved\n'):
    return {'schema': 1, 'configurations': [dict(namespace='previous-base', group='COMMON',
            data_id='shared-common.yaml', type='yaml', content=content)]}


class ConfigurationState(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.source = self.root / 'selected.json'
        self.write(document())
        self.args = m.parser().parse_args(['up', '--state-dir', str(self.root / 'state'),
                                           '--nacos-config-file', str(self.source)])
        self.e = m.Environment(self.args)
        self.e.check_ownership = Mock()

    def tearDown(self):
        self.temp.cleanup()

    def write(self, doc):
        m.protected_write(self.source, json.dumps(doc))

    def test_saved_copy_survives_removed_input_and_preserves_content_bytes(self):
        self.write(document('flag: preserved\r\n\n'))
        with self.e.locked():
            self.e.load(create=True)
            before = self.e.nacos_configurations
            self.source.unlink()
            self.args.nacos_config_file = None
            self.e.load()
            self.assertEqual(self.e.nacos_configurations, before)
            self.assertEqual(before[0]['content'], 'flag: preserved\r\n\n')
            self.assertEqual((self.e.state / 'binding.json').stat().st_mode & 0o777, 0o600)

    def test_changed_input_and_missing_explicit_input_are_rejected(self):
        with self.e.locked():
            self.e.load(create=True)
            original = (self.e.state / 'binding.json').read_bytes()
            self.write(document('flag: replacement\n'))
            with self.assertRaises(m.Failure): self.e.load()
            self.assertEqual((self.e.state / 'binding.json').read_bytes(), original)
            self.source.unlink()
            with self.assertRaises(OSError): self.e.load()

    def test_missing_saved_content_and_changed_digest_are_rejected(self):
        with self.e.locked():
            self.e.load(create=True)
            self.args.nacos_config_file = None
            path = self.e.state / 'binding.json'
            original = json.loads(path.read_text())
            for change in ('missing', 'content', 'digest'):
                value = copy.deepcopy(original)
                if change == 'missing': del value['nacos_configurations']
                elif change == 'content': value['nacos_configurations']['document']['configurations'][0]['content'] = 'changed'
                else: value['nacos_configurations']['sha256'] = '0' * 64
                config.atomic_record(path, value)
                with self.subTest(change=change), self.assertRaises(m.Failure): self.e.load()

    def test_duplicate_identity_and_unknown_fields_are_rejected(self):
        for kind in ('duplicate', 'unknown', 'type', 'namespace'):
            value = document()
            if kind == 'duplicate': value['configurations'] *= 2
            elif kind == 'unknown': value['force'] = True
            elif kind == 'type': value['configurations'][0]['content'] = True
            else: value['configurations'][0]['namespace'] = 'public'
            with self.subTest(kind=kind), self.assertRaises(m.Failure): config.normalize(value)

    def test_private_regular_input_and_unique_json_fields_required(self):
        self.source.chmod(0o644)
        with self.assertRaises(m.Failure): config.read_document(self.source)
        self.source.chmod(0o600)
        link = self.root / 'link'; link.symlink_to(self.source)
        with self.assertRaises(OSError): config.read_document(link)
        m.protected_write(self.source, '{"schema":1,"schema":1,"configurations":[]}')
        with self.assertRaises(m.Failure): config.read_document(self.source)

    def test_fifo_input_fails_without_waiting_for_a_writer(self):
        fifo = self.root / 'input-fifo'
        os.mkfifo(fifo, 0o600)
        result = subprocess.run([sys.executable, '-B', '-c',
            'import sys; import middleware_nacos_config as c; c.read_document(sys.argv[1])', str(fifo)],
            env={**os.environ, 'PYTHONPATH': str(Path(config.__file__).parent)},
            capture_output=True, text=True, timeout=3)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('private regular file', result.stderr)

    def test_failed_atomic_replace_retains_complete_previous_record(self):
        with self.e.locked():
            self.args.nacos_config_file = None
            self.e.load(create=True)
            old = (self.e.state / 'binding.json').read_bytes()
            self.args.nacos_config_file = str(self.source)
            with patch.object(config.os, 'replace', side_effect=OSError('interrupted')), self.assertRaises(OSError):
                self.e.load()
            self.assertEqual((self.e.state / 'binding.json').read_bytes(), old)
            self.assertEqual(list(self.e.state.glob('.binding-*')), [])
            self.e.load()
            self.assertEqual(self.e.nacos_configurations, document()['configurations'])

    def test_server_rejected_input_is_not_persisted(self):
        with self.e.locked():
            self.args.nacos_config_file = None; self.e.load(create=True)
            original = (self.e.state / 'binding.json').read_bytes()
            self.args.nacos_config_file = str(self.source)
            for field, value in [('group', '   '), ('data_id', '\t'), ('content', '\n'), ('type', 'bogus'),
                                 ('group', 'x'*129), ('data_id', 'bad/name'), ('group', 'a b'), ('data_id', 'a²'), ('data_id', '\U00010400.yaml')]:
                selected = document(); selected['configurations'][0][field] = value
                self.write(selected)
                with self.subTest(field=field), self.assertRaises(m.Failure): self.e.load()
                self.assertEqual((self.e.state / 'binding.json').read_bytes(), original)

    def test_non_up_cannot_adopt_configuration(self):
        with self.e.locked():
            self.args.nacos_config_file = None; self.e.load(create=True)
            self.args.nacos_config_file = str(self.source); self.args.action = 'verify'
            with self.assertRaises(m.Failure): self.e.load()
            self.assertNotIn('nacos_configurations', json.loads((self.e.state / 'binding.json').read_text()))


class FakeNacos:
    def __init__(self):
        self.namespaces = set()
        self.values = {}
        self.writes = []
        self.fail_after = None
    def login(self, **kwargs): pass
    def items(self, namespace):
        return [dict(groupName=k[1], dataId=k[2]) for k in self.values if k[0] == namespace]
    def call(self, method, path, params=None):
        if path == 'admin/core/namespace/list':
            return [dict(namespace=n) for n in sorted(self.namespaces)]
        if path == 'admin/core/namespace':
            self.namespaces.add(params['namespaceId']);self.writes.append(('namespace', params));return True
        key = (params['namespaceId'], params['groupName'], params['dataId'])
        if method == 'GET':
            if key not in self.values: raise init.HttpFailure(404, path)
            return self.values[key].copy()
        if self.fail_after is not None and len(self.values) >= self.fail_after:
            raise m.Failure('interrupted')
        self.values[key] = dict(content=params['content'], type=params['type'])
        self.writes.append((key, self.values[key]));return True


class ConfigurationOperations(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.e = m.Environment(m.parser().parse_args(['up', '--slots', '0']))
        self.e.state = Path(self.temp.name)
        self.e.nacos_configurations = document()['configurations']
        self.api = FakeNacos()
    def tearDown(self): self.temp.cleanup()

    def test_partial_write_retries_without_overwriting_completed_values(self):
        self.api.fail_after = 2
        with patch.object(init, 'Nacos', return_value=self.api):
            with self.assertRaises(m.Failure): init.initialize_nacos(self.e)
            before = self.api.values.copy()
            self.api.fail_after = None;init.initialize_nacos(self.e)
            for key, value in before.items(): self.assertEqual(self.api.values[key], value)
            count = len(self.api.writes);init.initialize_nacos(self.e)
            self.assertEqual(len(self.api.writes), count)
            self.assertEqual(len(self.api.values), len(init.CONFIGS) + len(self.e.nacos_configurations))
            init.verify_selected_configurations(self.e, self.api)

    def test_late_conflict_prevents_earlier_missing_writes(self):
        self.api.namespaces.add('previous-base')
        self.api.values[('previous-base', 'COMMON', 'shared-common.yaml')] = dict(content='foreign', type='yaml')
        with patch.object(init, 'Nacos', return_value=self.api), self.assertRaises(m.Failure):
            init.initialize_nacos(self.e)
        self.assertEqual(self.api.writes, [])

    def test_saved_selection_does_not_authorize_overwrite(self):
        self.api.namespaces.add('mars-local')
        self.e.nacos_configurations = [dict(namespace='mars-local', group='COMMON', data_id='shared-common.yaml', type='yaml', content='preserved')]
        self.api.values[('mars-local', 'COMMON', 'shared-common.yaml')] = dict(content=init.CONFIGS[('COMMON', 'shared-common.yaml')], type='yaml')
        with patch.object(init, 'Nacos', return_value=self.api), self.assertRaises(m.Failure): init.initialize_nacos(self.e)
        self.assertEqual(self.api.writes, [])

    def test_verify_detects_missing_or_changed_preserved_configuration(self):
        with patch.object(init, 'Nacos', return_value=self.api): init.initialize_nacos(self.e)
        key = ('previous-base', 'COMMON', 'shared-common.yaml')
        original = self.api.values.pop(key)
        with self.assertRaises(m.Failure): init.verify_selected_configurations(self.e, self.api)
        self.api.values[key] = {**original, 'type': 'text'}
        with self.assertRaises(m.Failure): init.verify_selected_configurations(self.e, self.api)


class ApplicationCredentials(unittest.TestCase):
    def test_client_login_refuses_admin_and_missing_credentials(self):
        e = Mock();e.owner = 'a' * 32;e.args.bind = '127.0.0.1';e.ports = {'nacos_http':28848};e.credentials = {'nacos':'admin-password'}
        api = init.Nacos(e)
        with self.assertRaises(m.Failure):api.login(client=True)
        e.credentials['nacos_client'] = 'client-password'
        api.call = Mock(return_value={'accessToken':'test-token', 'globalAdmin':True})
        with self.assertRaises(m.Failure):api.login(client=True)
        self.assertEqual(api.call.call_args.args[2]['password'], 'client-password')
        self.assertNotEqual(api.call.call_args.args[2]['username'], 'nacos')

    def test_failed_account_creation_reuses_saved_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            e = Mock();e.owner='a'*32;e.state=Path(directory);e.credentials={'nacos':'admin-password'}
            api = Mock();api.call.side_effect=m.Failure('account creation interrupted')
            with patch.object(init,'Nacos',return_value=api), patch.object(init,'paged',return_value=[]):
                with self.assertRaises(m.Failure):init.initialize_nacos_client(e)
                saved=json.loads((e.state/'credentials.json').read_text())
                with self.assertRaises(m.Failure):init.initialize_nacos_client(e)
                self.assertEqual(json.loads((e.state/'credentials.json').read_text()),saved)
                self.assertNotEqual(saved['nacos_client'],saved['nacos'])

    def test_unowned_existing_account_is_not_reset(self):
        e=Mock();e.owner='a'*32;e.credentials={'nacos':'admin-password'}
        api=Mock()
        with patch.object(init,'Nacos',return_value=api), patch.object(init,'paged',return_value=[{'username':'existing'}]):
            with self.assertRaises(m.Failure):init.initialize_nacos_client(e)
        self.assertNotIn('nacos_client',e.credentials)
        api.call.assert_not_called()


class SeededApplicationConfigurations(unittest.TestCase):
    def test_every_deployable_importing_its_application_configuration_is_seeded(self):
        """A deployable that imports DEFAULT_GROUP/<application name>.yaml needs a seeded configuration.

        Nacos only logs an empty-configuration warning when the data ID is missing, so a module added
        without a seed starts normally and the gap goes unnoticed.
        """
        repository = Path(__file__).resolve().parents[2]
        checked = []
        for path in sorted(repository.glob('mars-cloud-*/src/main/resources/config/application.yml')):
            text = path.read_text()
            if 'nacos:${spring.application.name}.yaml' not in text:
                continue
            name = re.search(r'^\s+application:\s*\n\s+name:\s*(\S+)\s*$', text, re.M).group(1)
            checked.append(name)
            self.assertIn(('DEFAULT_GROUP', name + '.yaml'), init.CONFIGS, path)
        self.assertIn('mars-cloud-monitor', checked)


class NumberedEnvironmentNamespaces(unittest.TestCase):
    """A numbered environment namespace is filled by an external workflow: initialization seeds only
    the missing items there and never compares content, while every other namespace stays strict."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.e = m.Environment(m.parser().parse_args(['up', '--slots', '0,2']))
        self.e.state = Path(self.temp.name)
        self.e.nacos_configurations = [dict(namespace='mars-slot-2', group='DEFAULT_GROUP',
                                            data_id='mars-cloud-gateway.yaml', type='yaml', content='selected: gateway\n')]
        self.api = FakeNacos()
        self.api.namespaces.add('mars-slot-2')
        self.api.values[('mars-slot-2', 'COMMON', 'shared-common.yaml')] = dict(content='synchronized: base\n', type='yaml')
        self.api.values[('mars-slot-2', 'DEFAULT_GROUP', 'mars-cloud-gateway.yaml')] = dict(content='synchronized: gateway\n', type='text')

    def tearDown(self):
        self.temp.cleanup()

    def initialize(self):
        with patch.object(init, 'Nacos', return_value=self.api):
            init.initialize_nacos(self.e)

    def test_existing_numbered_content_is_neither_compared_nor_overwritten(self):
        self.initialize()
        written = {key for key, _ in self.api.writes if key != 'namespace'}
        seeded = {('mars-slot-2', 'DEFAULT_GROUP', name) for name in
                  ('mars-cloud-auth-service.yaml', 'mars-cloud-upms-service.yaml', 'mars-cloud-sample-service.yaml', 'mars-cloud-monitor.yaml')}
        self.assertEqual(written, seeded | {('mars-local', group, data_id) for group, data_id in init.CONFIGS})
        self.assertEqual(self.api.values[('mars-slot-2', 'COMMON', 'shared-common.yaml')], dict(content='synchronized: base\n', type='yaml'))
        self.assertEqual(self.api.values[('mars-slot-2', 'DEFAULT_GROUP', 'mars-cloud-gateway.yaml')], dict(content='synchronized: gateway\n', type='text'))
        init.verify_selected_configurations(self.e, self.api)
        rows = {(row['namespace'], row['data_id']): row for row in json.loads((self.e.state / 'nacos-initialization.json').read_text())}
        self.assertFalse(rows[('mars-slot-2', 'shared-common.yaml')]['content_checked'])
        self.assertEqual(rows[('mars-slot-2', 'shared-common.yaml')]['sha256'], hashlib.sha256(b'synchronized: base\n').hexdigest())
        self.assertEqual(rows[('mars-slot-2', 'mars-cloud-gateway.yaml')]['type'], 'text')
        self.assertTrue(rows[('mars-local', 'shared-common.yaml')]['content_checked'])

    def test_missing_numbered_namespace_and_items_are_seeded_from_selection_or_template(self):
        self.api.namespaces.clear()
        self.api.values.clear()
        self.initialize()
        self.assertEqual(self.api.namespaces, {'mars-local', 'mars-slot-2'})
        self.assertEqual(self.api.values[('mars-slot-2', 'DEFAULT_GROUP', 'mars-cloud-gateway.yaml')], dict(content='selected: gateway\n', type='yaml'))
        self.assertEqual(self.api.values[('mars-slot-2', 'COMMON', 'shared-common.yaml')],
                         dict(content=init.CONFIGS[('COMMON', 'shared-common.yaml')], type='yaml'))
        init.verify_selected_configurations(self.e, self.api)

    def test_missing_or_empty_numbered_item_fails_verification_until_up_recreates_it(self):
        self.initialize()
        key = ('mars-slot-2', 'DEFAULT_GROUP', 'mars-cloud-monitor.yaml')
        removed = self.api.values.pop(key)
        with self.assertRaises(m.Failure) as failed:
            init.verify_selected_configurations(self.e, self.api)
        self.assertIn('/'.join(key), str(failed.exception))
        self.initialize()
        self.assertEqual(self.api.values[key], removed)
        init.verify_selected_configurations(self.e, self.api)
        self.api.values[key] = dict(content=' \n', type='yaml')
        with self.assertRaises(m.Failure):
            init.verify_selected_configurations(self.e, self.api)

    def test_base_conflict_still_stops_before_any_write(self):
        self.api.namespaces.add('mars-local')
        self.api.values[('mars-local', 'COMMON', 'shared-common.yaml')] = dict(content='foreign', type='yaml')
        with patch.object(init, 'Nacos', return_value=self.api), self.assertRaises(m.Failure):
            init.initialize_nacos(self.e)
        self.assertEqual(self.api.writes, [])
        with self.assertRaises(m.Failure):
            init.verify_selected_configurations(self.e, self.api)

    def test_namespace_outside_selected_environments_is_checked_strictly(self):
        self.e.nacos_configurations.append(dict(namespace='mars-slot-5', group='COMMON', data_id='shared-common.yaml',
                                                type='yaml', content='preserved\n'))
        self.api.namespaces.add('mars-slot-5')
        self.api.values[('mars-slot-5', 'COMMON', 'shared-common.yaml')] = dict(content='foreign', type='yaml')
        with patch.object(init, 'Nacos', return_value=self.api), self.assertRaises(m.Failure):
            init.initialize_nacos(self.e)
        self.assertEqual(self.api.writes, [])
        self.e.args.slots = [0, 2, 5]
        self.initialize()
        self.assertEqual(self.api.values[('mars-slot-5', 'COMMON', 'shared-common.yaml')], dict(content='foreign', type='yaml'))
        init.verify_selected_configurations(self.e, self.api)
