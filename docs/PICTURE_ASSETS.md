# Painting image assets

The backend reads an external painting image collection through the
`PICTURE_DIR` environment variable.

Historical server collection:

- approximately 9,069 JPG files
- approximately 696 MB
- historical path: `/root/autodl-tmp/auralink/backend/picture`

The image files are intentionally excluded from this Git repository.

Recommended deployment configuration:

```env
PICTURE_DIR=/data/auralink/picture
```

Before redistributing the collection, verify its source, copyright status,
and redistribution permissions.
