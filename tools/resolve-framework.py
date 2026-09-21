#!/usr/bin/env python3
"""Resolve a declared source revision against one snapshot of framework main."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def resolve(repo, declared, mode):
    if not re.fullmatch('[0-9a-f]{40}', declared):
        raise ValueError('framework-revision must contain one full commit SHA')
    git(repo, 'fetch', '--no-tags', 'origin', '+refs/heads/main:refs/remotes/origin/main')
    main = git(repo, 'rev-parse', 'refs/remotes/origin/main^{commit}')
    # Fetch by exact object id; a missing commit must fail, even with old local artifacts.
    git(repo, 'fetch', '--no-tags', 'origin', declared)
    if git(repo, 'rev-parse', declared + '^{commit}') != declared:
        raise ValueError('Declared revision is not a commit')
    contained = subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', declared, main]).returncode
    if contained not in (0, 1):
        raise ValueError('Unable to establish framework ancestry')
    if contained == 1 and mode == 'main':
        raise ValueError('Required framework revision has not entered main')
    return {'declared': declared, 'main_sha': main, 'sha': main if contained == 0 else declared,
            'source': 'main' if contained == 0 else 'candidate'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--framework', required=True)
    parser.add_argument('--revision-file', default=str(Path(__file__).resolve().parents[1] / '.ci/framework-revision'))
    parser.add_argument('--mode', choices=('candidate', 'main'), required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--checkout', action='store_true')
    args = parser.parse_args()
    try:
        repo = Path(args.framework).resolve()
        result = resolve(repo, Path(args.revision_file).read_text().strip(), args.mode)
        if args.checkout:
            if git(repo, 'status', '--porcelain'):
                raise ValueError('Refusing to replace a dirty framework checkout')
            git(repo, 'checkout', '--detach', result['sha'])
        Path(args.output).write_text(json.dumps(result, indent=2) + '\n')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                for key, value in result.items():
                    output.write(f'{key}={value}\n')
        print(json.dumps(result))
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
