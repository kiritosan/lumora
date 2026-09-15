# Project workflow

- Static HTML/CSS/JavaScript application; no frontend build step or runtime package dependencies.
- Run a local preview from this directory with `python -m http.server 8080 --bind 127.0.0.1`.
- Browser regression tests: install `playwright==1.49.1` with Python pip and run `python -m playwright install chromium` if its browser is not installed. Run `python -m unittest test_showcase -v` from this directory. Tests start their own local HTTP server and isolated browser contexts; they do not access the user's browser collection.
- JavaScript checks: `node --check physics.js`, `node --check physics-feedback.js`, and `node --check sw.js`. Inline JavaScript is exercised by the browser tests. Run `git diff --check` before finishing changes.
- Collectibles are stored in IndexedDB. Preserve existing user data; presentation-only interactions must not overwrite collections.
- Optional cloud sync lives in `cloud.js`: it calls Supabase PostgREST (`/rest/v1/shiguang_items`) with the user's own project URL + anon key + a shared sync key stored in `localStorage`. `supabase-setup.sql` creates the required table. Item payloads are AES-GCM encrypted client-side when `crypto.subtle` is available; rows are keyed by SHA-256(sync key) so the server never sees the raw key. Sync is last-write-wins on `item.updated`; deletions push tombstones.
- New app assets must be added to `sw.js` precache entries with a cache-version bump. Existing service-worker users may need to refresh again after the update is activated.
- Windows terminal commands use PowerShell syntax, not Bash heredocs.
