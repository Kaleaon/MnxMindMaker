# Mnx Drive map editor

A static browser editor for native `.mnxj` files in shared Google Drive folders.
The existing SQLite knowledge core is unchanged. Drive edits do not write back
accepted knowledge revisions.

Connect Google Drive, paste a shared folder URL, select a `.mnxj` map, edit its
concepts/connections and save back to the same file. New/imported maps save in
the folder. Sharing copies an editor link; default preserves existing Drive
permissions. Explicit selection can grant anyone-with-link view/edit if permitted.
Read-only users can inspect and download, but not change the open Drive map.
Every read and write is authorized by Google, using the visitor's own account.

## Google setup

See [setup instructions](dist/setup.html). Enable Google Drive API, configure
Google Auth Platform and create a Web application OAuth client with authorized
JavaScript origin `https://kaleaon.github.io` (no path). Add named test users for
the first rollout. A Drive connector in this chat does not supply credentials to
the separately hosted browser app. No OAuth client secret or Picker key is needed.

This folder-first workflow uses restricted `https://www.googleapis.com/auth/drive`:
`drive.file` does not authorize existing children merely because a folder was
selected. Public distribution may require Google verification and a security
assessment. The token stays in tab memory, is sent only to Google, and is never
stored in browser storage or sent to GitHub. Browser storage holds only an
optional public-client-ID setting.

Set Actions repository variable `GOOGLE_OAUTH_CLIENT_ID` to the public client ID.
The workflow embeds it into config.js; run the workflow after updating the value.
Never supply client secrets/access tokens/refresh tokens. For a local test use
Settings and authorize `http://localhost:8000`.

## GitHub Pages

Initial publication can use `gh-pages` without merging unrelated app work.
For automatic publication after the deployment workflow is merged, switch
Settings → Pages → Source to **GitHub Actions**. The workflow deploys only
`tools/map-editor/dist`, runs checks and supplies the public client ID.
It does not publish SQLite files or private Drive contents.

```sh
cd tools/map-editor
npm test
npm run check
python -m http.server 8000 --directory dist
```

Open http://localhost:8000. There are no npm dependencies or build step.

## Format and limits

Supports mnx.interchange major 1 with min reader 1.0. IDs, attributes, dimensions,
unknown fields and envelope metadata are preserved. Node deletion removes
connected edges and clears children's parent_id. Node positions, labels, types,
descriptions and connections are editable; the attribute/dimension inspector is
read-only to preserve provenance. Existing Drive filenames stay unchanged when
the graph title changes.

Manual saves check Drive versions and use If-Match if an ETag is returned. Without
one, simultaneous check/write races remain possible. Download conflicting drafts
and reload; Drive revisions are another recovery path. Writes are never retried
automatically. Failed saves retain the draft in the open tab, not durable storage.
Limits: 10 MiB, 10,000 nodes, 30,000 connections. Folder listing follows pagination
and supports resource keys/shared-drive flags. A map can open even if the visitor
cannot browse its parent folder. Optional browser WebMCP has one read-only tool.
