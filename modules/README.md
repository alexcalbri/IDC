# IdeasCore Modules

This directory is the default source location for modules developed in the
official repository.

The official module repository URL is:

```text
https://github.com/alexcalbri/IDC/tree/master/modules
```

The server accepts that normal GitHub URL and converts it internally to the
GitHub Contents API. The server owner can point `MODULE_CATALOG_URL` to another
GitHub repository when they want to install non-official modules. A direct
Contents API URL also works, for example
`https://api.github.com/repos/owner/repository/contents/modules?ref=main`.

The server lists every folder in that directory and treats a folder as
installable only when it contains a valid `module.json` manifest. Removing a
module folder from the remote repository prevents new installations, but it does
not break servers that already installed the module.

## Installable module manifest

Each installable module folder must contain `module.json`:

```json
{
  "id": "example",
  "displayName": "Example",
  "description": "Example business module.",
  "version": "0.1.0",
  "packageUrl": "https://example.com/modules/example.zip",
  "packageSha256": "64_hex_characters_when_available",
  "views": [],
  "permissions": []
}
```

Validation rules:

- `id` must match the folder name and the pattern `[a-z][a-z0-9_-]{0,62}`.
- `displayName`, `description` and `version` must not be blank.
- `packageUrl`, when present, must start with `http://` or `https://`.
- `packageSha256`, when present, must be a 64-character SHA-256 hex digest.
- `locked` is ignored for remote modules; base locked modules belong to Core.
