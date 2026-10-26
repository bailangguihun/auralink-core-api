import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import io

spec = importlib.util.spec_from_file_location('community_bundle', Path(__file__).resolve().parents[1] / 'scripts/community-release-bundle.py')
bundle = importlib.util.module_from_spec(spec); spec.loader.exec_module(bundle)


class CommunityBundleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in bundle.FILES:
            path = self.root/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('fixture')
        (self.root/'package.json').write_text(json.dumps({'dependencies': {'fixture': '1.0.0'}}))
        (self.root/'package-lock.json').write_text(json.dumps({'lockfileVersion':3,'packages':{
            '': {'dependencies': {'fixture': '1.0.0'}}, 'node_modules/fixture': {
                'version':'1.0.0','integrity':'sha512-fixture','resolved':'https://registry.npmjs.org/fixture/-/fixture-1.0.0.tgz'}}}))
        path = self.root/'node_modules/fixture'; path.mkdir(parents=True)
        (path/'package.json').write_text('{"version":"1.0.0"}')
        self.provenance = dict(repository='bailangguihun/auralink-core-api',ref='refs/heads/release',commit='a'*40,run_id='1',run_attempt='1')

    def build(self):
        return bundle.materialize(self.root,self.provenance,'v22.13.0')

    def test_reproducible_full_inventory_and_mutation(self):
        a,m = self.build(); self.assertEqual((a,m),self.build())
        manifest=json.loads(m); z=zipfile.ZipFile(io.BytesIO(a))
        self.assertEqual(z.namelist(),[p['path'] for p in manifest['files']])
        (self.root/'src/main.mjs').write_text('changed'); self.assertNotEqual(self.build(),(a,m))

    def test_unexpected_dependency_and_symlink_rejected(self):
        other=self.root/'node_modules/dev-only';other.mkdir();(other/'package.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'unexpected/dev'): self.build()
        (other/'package.json').unlink();other.rmdir()
        (self.root/'node_modules/fixture/link').symlink_to('/etc/passwd')
        with self.assertRaisesRegex(ValueError,'symlink'):self.build()

    def test_dependency_drift_and_missing_file_rejected(self):
        (self.root/'node_modules/fixture/package.json').write_text('{"version":"2"}')
        with self.assertRaisesRegex(ValueError,'dependency version'):self.build()
        (self.root/'node_modules/fixture/package.json').unlink()
        with self.assertRaisesRegex(ValueError,'missing production'):self.build()

    def test_wrong_source_and_runtime_rejected(self):
        self.provenance['ref']='refs/heads/main'
        with self.assertRaisesRegex(ValueError,'release branch'):self.build()
        self.provenance['ref']='refs/heads/release'
        with self.assertRaisesRegex(ValueError,'Node minimum'):bundle.materialize(self.root,self.provenance,'v22.1.0')
