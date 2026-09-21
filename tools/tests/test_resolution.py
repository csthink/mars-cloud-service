import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('resolver', Path(__file__).resolve().parents[1] / 'resolve-framework.py')
r = importlib.util.module_from_spec(spec); spec.loader.exec_module(r)


class ResolutionTest(unittest.TestCase):
    def test_candidate_main_and_missing_revision(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); remote = root / 'remote'; client = root / 'client'
            subprocess.run(['git', 'init', '-q', '-b', 'main', str(remote)], check=True)
            def git(*args): return r.git(remote, *args)
            git('config', 'user.name', 'Test'); git('config', 'user.email', 'test@example.invalid')
            (remote / 'source').write_text('main'); git('add', 'source'); git('commit', '-qm', 'main')
            main = git('rev-parse', 'HEAD'); git('checkout', '-qb', 'candidate')
            (remote / 'source').write_text('candidate'); git('commit', '-qam', 'candidate'); candidate = git('rev-parse', 'HEAD')
            subprocess.run(['git', 'clone', '-q', str(remote), str(client)], check=True)
            self.assertEqual(r.resolve(client, candidate, 'candidate')['sha'], candidate)
            self.assertEqual(r.resolve(client, candidate, 'candidate')['source'], 'candidate')
            with self.assertRaises(ValueError): r.resolve(client, candidate, 'main')
            with self.assertRaises(subprocess.CalledProcessError): r.resolve(client, '0' * 40, 'candidate')
            with self.assertRaises(ValueError): r.resolve(client, 'main', 'candidate')
            git('checkout', '-q', 'main'); git('merge', '--ff-only', 'candidate')
            result = r.resolve(client, candidate, 'main')
            self.assertEqual(result['sha'], candidate); self.assertEqual(result['source'], 'main')
            (remote / 'source').write_text('new main'); git('commit', '-qam', 'advance')
            result = r.resolve(client, candidate, 'candidate')
            self.assertEqual(result['sha'], git('rev-parse', 'HEAD'))
            self.assertNotEqual(result['sha'], candidate)
            self.assertEqual(result['declared'], candidate)


if __name__ == '__main__': unittest.main()
