# Workspace file operations

## Named roots

`instance` is the Minecraft instance root and the default. `koil` is Koil's internal data directory. `automation` contains the active runtime KTL tree. `project` exists only in a development checkout.

Paths are always relative to a named root. Absolute paths, traversal, sensitive credentials, and arbitrary execution are unavailable.

## Directory and file creation

- Create a directory with `workspace.mkdir`. Verify the result says the path is a directory.
- Create a new regular UTF-8 file with `workspace.create`. Its parent directory must already exist.
- Never use file creation to represent a directory.
- Use `workspace.stat` when the current path type or existence is uncertain.

## Reading and searching

- Use `workspace.search` with the narrowest path, glob, query, match mode, and output mode.
- Use `outputMode=count` for totals, `files` for paths, `matches` for exact words/columns, and `lines` only for surrounding source.
- Use `workspace.read` with a small exact line range. A partial result has `hasMore=true` and `nextStartLine`; it is not the complete file.

## Editing

1. Read or stat the target and retain its returned SHA-256 revision.
2. Use `workspace.replace` for an exact known occurrence.
3. Use `workspace.append` to add exact content to an existing file.
4. Use `workspace.write` only when replacing the complete file is intentional.
5. Pass the latest `expectedHash` for every existing-file mutation.
6. Read the structured result and real diff/reread evidence. A submitted call or missing exception is not proof of a successful edit.

Use `workspace.create` only for a nonexistent file. Use `workspace.write`, `workspace.replace`, or `workspace.append` only for an existing regular file.
