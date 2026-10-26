#!/usr/bin/env python3
"""Package-only Community bundle. Never connects to a server or runs SQL."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

FILES = ('package.json', 'package-lock.json', 'db/001_foundation.sql',
         'src/main.mjs', 'src/server.mjs', 'src/store.mjs', 'src/database.mjs',
         'src/core.mjs', 'src/errors.mjs', 'src/media.mjs')


def require(value, message):
    if not value:
        raise ValueError(message)


def materialize(source, provenance, node_version):
    require(provenance['repository'] == 'bailangguihun/auralink-core-api', 'repository')
    require(provenance['ref'] == 'refs/heads/release', 'release branch required')
    require(re.fullmatch('[a-f0-9]{40}', provenance['commit']), 'commit')
    for key in ('run_id', 'run_attempt'):
        require(re.fullmatch('[1-9][0-9]{0,19}', provenance[key]), key)
    require(re.fullmatch(r'v(?:22|24)\.[0-9]+\.[0-9]+', node_version), 'tested Node major')
    version = tuple(map(int, node_version[1:].split('.')))
    require(version >= (22, 13, 0), 'Node minimum')
    lock = json.loads((source / 'package-lock.json').read_bytes())
    require(lock['lockfileVersion'] == 3, 'lockfile version')
    require(json.loads((source / 'package.json').read_bytes())['dependencies'] ==
            lock['packages']['']['dependencies'], 'manifest/lock dependencies')
    packages = {p: v for p, v in lock['packages'].items() if p and not v.get('dev')}
    for name, value in packages.items():
        require(name.startswith('node_modules/') and '..' not in name.split('/'), 'dependency path')
        require(value.get('integrity') and value.get('resolved', '').startswith('https://registry.npmjs.org/'), 'locked registry dependency')
        installed = source / name / 'package.json'
        require(installed.is_file(), 'missing production dependency: ' + name)
        require(json.loads(installed.read_bytes())['version'] == value['version'], 'dependency version')
    files = {p: source / p for p in FILES}
    for path in (source / 'node_modules').rglob('*'):
        require(not path.is_symlink(), 'dependency symlink')
        if path.is_dir():
            continue
        name = path.relative_to(source).as_posix()
        if name == 'node_modules/.package-lock.json':
            continue  # npm install metadata is not executed or shipped.
        require(any(name.startswith(p + '/') for p in packages), 'unexpected/dev dependency: ' + name)
        files[name] = path
    archive, records = io.BytesIO(), []
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_STORED) as z:
        for name, path in sorted(files.items()):
            require(path.is_file() and not path.is_symlink(), 'regular source file required')
            require(not any(p.is_symlink() for p in path.parents if p != source.parent), 'symlink ancestor')
            raw = path.read_bytes()
            require(len(raw) <= 32 * 1024 * 1024, 'file size')
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3; info.external_attr = 0o100600 << 16
            z.writestr(info, raw)
            records.append(dict(path=name, bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest()))
    raw = archive.getvalue()
    manifest = dict(schema='artlive-community-bundle/v1', **provenance,
                    build_node=node_version, runtime_node_min='22.13.0', contract_version=1,
                    database_schema_version=1, archive_sha256=hashlib.sha256(raw).hexdigest(), files=records)
    return raw, (json.dumps(manifest, sort_keys=True, separators=(',', ':'))+'\n').encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['create', 'verify'])
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--node-version', required=True)
    for key in ('repository', 'ref', 'commit', 'run-id', 'run-attempt'):
        parser.add_argument('--'+key, required=True)
    args = parser.parse_args()
    raw, manifest = materialize(args.source.resolve(), {k: getattr(args, k) for k in
        ('repository', 'ref', 'commit', 'run_id', 'run_attempt')}, args.node_version)
    if args.mode == 'create':
        args.output.mkdir(parents=True, exist_ok=False)
        for name, data in [('community.zip', raw), ('manifest.json', manifest)]:
            with (args.output / name).open('xb') as f:
                f.write(data)
    else:
        require((args.output/'community.zip').read_bytes() == raw, 'archive differs from locked source')
        require((args.output/'manifest.json').read_bytes() == manifest, 'manifest differs from source/run')
    print('COMMUNITY=NOT_DEPLOYED PACKAGE_'+args.mode.upper()+'_PASS')


if __name__ == '__main__':
    main()
