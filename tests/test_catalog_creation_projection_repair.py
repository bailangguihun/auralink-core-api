"""Pure preparation tests. No connection, application or provider calls."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('projection', Path(__file__).parents[1] / 'scripts/catalog_creation_projection_repair.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture():
    return dict(painting_id='23c2e7c7-3898-5ecd-9ca0-dc47fc7b2d39',
                asset_id='43dce8c3-bd8d-5837-893c-735b2d379987',
                input_batch_id='00000000-0000-4000-8000-000000000301', input_revision=1,
                asset_batch_id='00000000-0000-4000-8000-000000000301', asset_revision=1,
                relative_path='synthetic.jpg', storage_uri='batch://isolated/synthetic.jpg',
                sha256='a'*64, file_size=128, mime_type='image/jpeg',
                title='离线合成画作', author='合成作者', category='合成类别')


class PreparationTest(unittest.TestCase):
    def test_only_three_inserts_and_no_overwrite_or_generation(self):
        sql = module.render(fixture())
        self.assertEqual(sql.count('INSERT INTO '), 3)
        for forbidden in ('ON CONFLICT', 'UPDATE ', 'DELETE ', 'setval(', 'TRUNCATE', 'generate_with_image'):
            self.assertNotIn(forbidden, sql)
        self.assertIn('PROJECTION_ALREADY_EXISTS_OR_PARTIAL_DO_NOT_OVERWRITE', sql)

    def test_rejects_traversal_and_mime_mismatch(self):
        for path in ('../x.jpg', '/x.jpg', 'a/../../x.jpg', 'a\\x.jpg', '', 'a//x.jpg'):
            with self.subTest(path=path), self.assertRaises((ValueError, TypeError)):
                module.render(dict(fixture(), relative_path=path))
        with self.assertRaises(ValueError):
            module.render(dict(fixture(), mime_type='audio/wav'))

    def test_canonical_ids_hash_size_and_schema_are_mandatory(self):
        for changes in ({'painting_id':'not-a-uuid'}, {'sha256':'bad'}, {'file_size':True},
                        {'input_revision':0}, {'file_size':10485761}, {'approval':True}):
            with self.subTest(changes=changes), self.assertRaises((ValueError, TypeError)):
                module.render(dict(fixture(), **changes))

    def test_metadata_remains_literal_even_inside_guard_body(self):
        sql = module.render(dict(fixture(), title="夏' ; $repair_guard$ SELECT 1; --"))
        self.assertIn('DO $repair_guardx$', sql)
        self.assertIn("夏'' ; $repair_guard$ SELECT 1; --", sql)


if __name__ == '__main__':
    unittest.main()
