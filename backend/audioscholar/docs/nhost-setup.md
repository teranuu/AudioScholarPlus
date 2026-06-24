# Nhost Storage API Setup

This backend uses Nhost Storage for uploaded audio, PowerPoint, profile image, and generated file assets through `NhostStorageService`.

## 1. Create the Nhost project

1. Open the Nhost dashboard and create or select your project.
2. Copy the project subdomain, region, and admin secret.
3. Build the storage endpoint in this format:

```env
NHOST_STORAGE_URL=https://<subdomain>.storage.<region>.nhost.run/v1/files
NHOST_ADMIN_SECRET=<your-admin-secret>
NHOST_STORAGE_BUCKET_ID=default
```

Use a custom bucket name for `NHOST_STORAGE_BUCKET_ID` only after creating that bucket in Nhost Storage.

## 2. Configure the backend

Set the variables above in your local shell, `.env` loader, or deployment environment. The Spring properties are already mapped in `application.properties`:

```properties
nhost.storage.url=${NHOST_STORAGE_URL}
nhost.storage.admin-secret=${NHOST_ADMIN_SECRET}
nhost.storage.bucket-id=${NHOST_STORAGE_BUCKET_ID:default}
```

Keep `NHOST_ADMIN_SECRET` server-side only. Do not expose it in web or mobile clients.

## 3. Configure Nhost permissions

The backend uploads, deletes, and processing downloads use the admin secret. File references are still stored as URLs in the format:

```text
https://<subdomain>.storage.<region>.nhost.run/v1/files/<file-id>
```

In Nhost, configure `storage.files` permissions for any client-side playback or download access. Backend processing does not require public read permissions because it authenticates with `NHOST_ADMIN_SECRET`. If files should be public to users, allow the `public` role to select from `storage.files` for that bucket. If files should be user-owned, restrict reads by the uploaded user or your app's ownership rules.

## 4. Verify

1. Start the backend with the Nhost environment variables set.
2. Upload an audio file or profile image through the app.
3. Confirm the backend stores an Nhost file ID such as `nhostFileId`, `nhostPptxFileId`, or a profile image URL.
4. Open the generated `/v1/files/<file-id>` URL and confirm the file downloads or streams according to your Nhost permissions.

Reference docs:

- Nhost Storage upload API: https://docs.nhost.io/reference/storage/post-files
- Nhost Storage permissions tutorial: https://docs.nhost.io/getting-started/tutorials/react/5-file-uploads
- Nhost admin secret guidance: https://docs.nhost.io/reference/javascript/nhost-js/fetch
- Nhost CDN and file URLs: https://docs.nhost.io/products/storage/cdn
# Large audio uploads

The backend accepts audio files up to 100 MiB. The Nhost bucket must use the same limit or uploads will fail after
the backend has accepted them. Set the configured bucket's `max_upload_file_size` to `104857600` bytes in the Nhost
Dashboard under **Storage > Bucket settings**. The backend defaults to the `default` bucket through
`NHOST_STORAGE_BUCKET_ID`.
