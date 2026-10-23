import copy
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from catalog_projection_bulk import classify, render_bulk
from test_catalog_creation_projection_repair import fixture


def snapshot():
    r = fixture()
    c = dict(r, catalog_status='ACTIVE', image_display_status='READY', media_status='ACTIVE')
    p = dict(id=21, public_id=r['painting_id'], source_key='catalog-projection:' + r['painting_id'],
             status='ACTIVE', image_available=True, visible_in_gallery=True,
             image_storage_name=r['relative_path'], image_asset_id=31)
    a = dict(id=31, public_id=r['asset_id'], owner_user_id=None,
             storage_key='catalog/' + r['asset_id'] + '/' + r['relative_path'], sha256=r['sha256'],
             asset_type='IMAGE', semantic_type='IMAGE', source_type='CATALOG_REFERENCE', visibility='PUBLIC', status='ACTIVE')
    l = dict(catalog_painting_id=r['painting_id'], catalog_asset_id=r['asset_id'],
             jpa_painting_id=21, jpa_asset_id=31, input_batch_id=r['input_batch_id'], input_revision=1)
    return dict(catalog=[c], paintings=[p], assets=[a], projections=[l])


class BulkTest(unittest.TestCase):
    def state(self, d): return classify(d)['records'][0]['state']

    def test_valid_and_all_missing(self):
        d=snapshot(); before=copy.deepcopy(d)
        self.assertTrue(classify(d)['creation_ready']); self.assertEqual(d, before)
        for key in ('paintings','assets','projections'): d[key]=[]
        self.assertEqual(self.state(d), 'FULLY_MISSING')
        self.assertFalse(classify(d)['creation_ready'])

    def test_asset_only_and_painting_asset_without_ledger_are_partial(self):
        d=snapshot(); d['projections']=[]
        self.assertEqual(self.state(d),'PARTIAL')
        d['paintings']=[]
        self.assertEqual(self.state(d),'PARTIAL')

    def test_conflicting_identity_status_links_and_foreign_keys(self):
        for table, key, value in [('paintings','status','INACTIVE'),('paintings','image_asset_id',9),
                                 ('assets','sha256','b'*64),('assets','visibility','PRIVATE'),
                                 ('assets','owner_user_id',17),('assets','source_type','UPLOAD'),
                                 ('projections','input_revision',2),('projections','jpa_asset_id',99)]:
            with self.subTest(table=table,key=key):
                d=snapshot();d[table][0][key]=value
                self.assertEqual(self.state(d),'CONFLICT')

    def test_alias_collision_is_not_fully_missing(self):
        for table in ('paintings','assets'):
            d=snapshot(); row=d[table][0]; row['public_id']='different-identity'
            d['projections']=[]
            self.assertEqual(self.state(d),'CONFLICT')

    def test_ledger_orphan_and_duplicate_canonical_identity_refuse(self):
        d=snapshot();d['assets']=[]
        self.assertEqual(self.state(d),'CONFLICT')
        d=snapshot();d['catalog'].append(copy.deepcopy(d['catalog'][0]))
        self.assertTrue(all(r['state']=='CONFLICT' for r in classify(d)['records']))

    def test_bulk_scope_validation(self):
        for rows in ([],[fixture(),fixture()]):
            with self.assertRaises(ValueError):render_bulk(rows)
        with self.assertRaises(ValueError):render_bulk([dict(fixture(),file_size=0)])

    def test_bulk_does_not_overwrite_or_commit_per_record(self):
        sql=render_bulk([fixture()])
        self.assertEqual(sql.count('\nCOMMIT;'),1)
        self.assertEqual(sql.count('INSERT INTO '),3)
        for forbidden in ('ON CONFLICT','UPDATE ','DELETE ','setval(','TRUNCATE'):
            self.assertNotIn(forbidden,sql)


if __name__=='__main__':unittest.main()
