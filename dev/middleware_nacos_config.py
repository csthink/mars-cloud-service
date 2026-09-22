"""Validate and retain explicitly selected Nacos initialization content."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
from middleware import Failure


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def normalize(value):
    if not isinstance(value, dict) or set(value) != {'schema', 'configurations'} or type(value['schema']) is not int or value['schema'] != 1:
        raise Failure('Invalid Nacos configuration document')
    items = value['configurations']
    if not isinstance(items, list) or not items or len(items) > 10000:
        raise Failure('Nacos configuration list must be nonempty and bounded')
    keys, rows = set(), []
    for row in items:
        fields = {'namespace', 'group', 'data_id', 'type', 'content'}
        if not isinstance(row, dict) or set(row) != fields or any(not isinstance(v, str) for v in row.values()):
            raise Failure('Invalid Nacos configuration entry')
        if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}', row['namespace']) or row['namespace'] == 'public':
            raise Failure('An explicit non-public Nacos namespace is required')
        for name in ('group', 'data_id'):
            if not row[name] or len(row[name]) > 255 or any(ord(c) < 32 or ord(c) == 127 for c in row[name]):
                raise Failure('Invalid Nacos configuration identifier')
        if not re.fullmatch(r'[a-z][a-z0-9_-]{0,31}', row['type']) or len(row['content'].encode()) > 1024 * 1024:
            raise Failure('Invalid Nacos configuration type or size')
        key = (row['namespace'], row['group'], row['data_id'])
        if key in keys:
            raise Failure('Duplicate Nacos configuration identity')
        keys.add(key)
        rows.append(dict(row))
    return {'schema': 1, 'configurations': sorted(rows, key=lambda r: (r['namespace'], r['group'], r['data_id']))}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Failure('Duplicate JSON field in Nacos configuration document')
        result[key] = value
    return result


def read_document(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd) as stream:
        meta = os.fstat(stream.fileno())
        if not stat.S_ISREG(meta.st_mode) or meta.st_uid != os.getuid() or meta.st_mode & 0o077 or meta.st_size > 16 * 1024 * 1024:
            raise Failure('Nacos configuration input must be a private regular file owned by this user')
        try:
            return normalize(json.load(stream, object_pairs_hook=unique_object))
        except (ValueError, UnicodeError):
            raise Failure('Invalid Nacos configuration JSON') from None


def atomic_record(path, value):
    if path.is_symlink():
        raise Failure('Refusing to replace a symbolic link')
    descriptor, temporary = tempfile.mkstemp(prefix='.binding-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(canonical(value) + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load_plan(e):
    path = e.state / 'binding.json'
    record = json.loads(path.read_text())
    saved = record.get('nacos_configurations')
    mode = record.get('nacos_configuration_mode', 'default')
    if mode not in ('default', 'explicit-v1') or (mode == 'explicit-v1') != (saved is not None):
        raise Failure('Saved Nacos configuration mode or content is missing')
    document = None
    if saved is not None:
        if not isinstance(saved, dict) or set(saved) != {'sha256', 'document'}:
            raise Failure('Invalid saved Nacos configuration record')
        document = normalize(saved['document'])
        if hashlib.sha256(canonical(document).encode()).hexdigest() != saved['sha256']:
            raise Failure('Saved Nacos configuration digest differs')
    selected = e.args.nacos_config_file
    if selected:
        if e.args.action != 'up':
            raise Failure('Select Nacos initialization content only with up')
        requested = read_document(Path(selected))
        if document is not None and requested != document:
            raise Failure('This environment already has different approved Nacos initialization content')
        if document is None:
            document = requested
            record['nacos_configuration_mode'] = 'explicit-v1'
            record['nacos_configurations'] = dict(document=document,
                sha256=hashlib.sha256(canonical(document).encode()).hexdigest())
            atomic_record(path, record)
    return document['configurations'] if document else []
