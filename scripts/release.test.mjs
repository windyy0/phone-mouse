import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { prepareVersion, publishTag } from "./release.mjs";
import { readVersions, releaseNotesByLanguage } from "./create-update-manifest.mjs";

const script = fileURLToPath(new URL("./release.mjs", import.meta.url));
const versionFiles = ["android-app/app/build.gradle.kts", "windows-server/Cargo.toml", "windows-server/Cargo.lock", "CHANGELOG.md"];
const read = (root, file) => fs.readFileSync(path.join(root, file), "utf8");
const write = (root, file, body) => {
  fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
  fs.writeFileSync(path.join(root, file), body);
};
const snapshot = (root) => versionFiles.map((file) => read(root, file));

const withFixture = (block, newline = "\n") => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "phone-mouse-release-"));
  const root = path.join(directory, "work");
  const content = {
    "android-app/app/build.gradle.kts": 'versionCode = 3\nversionName = "1.0.0"\n',
    "windows-server/Cargo.toml": '[package]\nname = "phone-mouse-server"\nversion = "1.0.0"\n',
    "windows-server/Cargo.lock": '# Generated\nversion = 4\n\n[[package]]\nname = "phone-mouse-server"\nversion = "1.0.0"\ndependencies = ["serde"]\n\n[[package]]\nname = "serde"\nversion = "1.0.228"\nsource = "registry+https://github.com/rust-lang/crates.io-index"\n',
    "CHANGELOG.md": '# 更新记录\n\n## 未发布\n\n### 中文\n\n- 新功能。\n\n### English\n\n- New feature.\n\n## 1.0.0\n\n### 中文\n\n- 首次发布。\n\n### English\n\n- Initial release.\n',
    ".github/workflows/release.yml": "name: Release test fixture\n",
    "scripts/create-update-manifest.mjs": "// Test fixture\n",
    "scripts/release.mjs": "// Test fixture\n",
  };
  try {
    for (const [file, body] of Object.entries(content)) write(root, file, body.replaceAll("\n", newline));
    return block(root, directory);
  } finally {
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    fs.rmSync(directory, { recursive: true, force: true });
  }
};

const repository = (root, directory) => {
  const run = (cwd, args) => execFileSync("git", args, { cwd, encoding: "utf8", windowsHide: true }).trim();
  const git = (...args) => run(root, args);
  const remote = path.join(directory, "remote.git");
  git("init", "--initial-branch=main");
  run(directory, ["init", "--bare", remote]);
  fs.mkdirSync(path.join(directory, "empty-hooks"));
  for (const cwd of [root, remote]) {
    run(cwd, ["config", "core.hooksPath", path.join(directory, "empty-hooks")]);
    run(cwd, ["config", "core.autocrlf", "false"]);
  }
  git("config", "user.name", "Release Test");
  git("config", "user.email", "release@example.invalid");
  git("config", "commit.gpgsign", "false");
  git("config", "tag.gpgsign", "false");
  git("add", ".");
  git("commit", "-m", "Initial fixture");
  git("tag", "v1.0.0");
  git("remote", "add", "origin", remote);
  git("push", "--quiet", "origin", "main", "refs/tags/v1.0.0");
  prepareVersion("1.0.1", root);
  git("add", ".");
  git("commit", "-m", "Prepare release");
  git("push", "--quiet", "origin", "main");
  return { git, remote, remoteGit: (...args) => run(remote, args) };
};

test("version preparation updates all version files, preserves dependencies, and keeps bilingual notes", () => {
  for (const newline of ["\n", "\r\n"]) withFixture((root) => {
    const oldLock = read(root, "windows-server/Cargo.lock");
    const result = prepareVersion("1.0.1", root);
    assert.deepEqual(result, { version: "1.0.1", versionCode: 4 });
    assert.deepEqual(readVersions(root), { androidName: "1.0.1", androidCode: 4, windows: "1.0.1" });
    assert.equal(read(root, "windows-server/Cargo.lock"), oldLock.replace(`version = "1.0.0"`, `version = "1.0.1"`));
    assert.deepEqual(releaseNotesByLanguage("1.0.1", read(root, "CHANGELOG.md")), { zh: "- 新功能。", en: "- New feature." });
    assert.deepEqual(releaseNotesByLanguage("1.0.0", read(root, "CHANGELOG.md")), { zh: "- 首次发布。", en: "- Initial release." });
    assert.throws(() => releaseNotesByLanguage("未发布", read(root, "CHANGELOG.md")));
    if (newline === "\r\n") for (const text of snapshot(root)) assert.ok(!/(?<!\r)\n/.test(text));
    const prepared = snapshot(root);
    assert.throws(() => prepareVersion("1.0.2", root));
    assert.deepEqual(snapshot(root), prepared);
  }, newline);
});

test("invalid, repeated, and lower versions leave every file unchanged", () => withFixture((root) => {
  const before = snapshot(root);
  for (const value of ["1.0.0", "0.9.9", "v1.0.1", "01.0.1", "1.0.1-beta", "1.0.1;echo bad", "1.0"]) {
    assert.throws(() => prepareVersion(value, root));
    assert.deepEqual(snapshot(root), before);
  }
}));

test("missing bilingual notes, duplicate headings, invalid counters, and stale locks stop before writes", () => {
  const edits = [
    ["CHANGELOG.md", (text) => text.replace("- New feature.", "")],
    ["CHANGELOG.md", (text) => text.replace("## 未发布", "## 未发布\n\n## 未发布")],
    ["CHANGELOG.md", (text) => text + "\n## 1.0.1\n"],
    ["android-app/app/build.gradle.kts", (text) => text.replace("versionCode = 3", "versionCode = 2100000000")],
    ["windows-server/Cargo.toml", (text) => text.replace("1.0.0", "1.0.2")],
    ["windows-server/Cargo.lock", (text) => text.replace("1.0.0", "0.9.0")],
  ];
  for (const [file, edit] of edits) withFixture((root) => {
    write(root, file, edit(read(root, file)));
    const before = snapshot(root);
    assert.throws(() => prepareVersion("1.0.1", root));
    assert.deepEqual(snapshot(root), before);
  });
});

test("version CLI updates a fixture and rejects missing or extra arguments", () => withFixture((root) => {
  const invalid = spawnSync(process.execPath, [script, "version"], { cwd: root, encoding: "utf8" });
  assert.equal(invalid.status, 1);
  execFileSync(process.execPath, [script, "version", "1.0.1"], { cwd: root });
  assert.equal(readVersions(root).androidCode, 4);
  const before = snapshot(root);
  const extra = spawnSync(process.execPath, [script, "version", "1.0.2", "extra"], { cwd: root });
  assert.equal(extra.status, 1);
  assert.deepEqual(snapshot(root), before);
}));

test("release dry run validates a real repository without changing local or remote tags", () => withFixture((root, dir) => {
  const { git } = repository(root, dir);
  const before = git("show-ref");
  const remoteBefore = git("ls-remote", "--refs", "origin");
  const result = publishTag({ root, dryRun: true });
  assert.equal(result.tag, "v1.0.1");
  assert.equal(result.head, git("rev-parse", "HEAD"));
  assert.equal(git("show-ref"), before);
  assert.equal(git("ls-remote", "--refs", "origin"), remoteBefore);
  const output = execFileSync(process.execPath, [script, "release", "--dry-run"], { cwd: root, encoding: "utf8" });
  assert.ok(output.includes("v1.0.1"));
  assert.equal(git("show-ref"), before);
}));

test("release publishes an annotated tag for the checked commit and refuses a second publication", () => withFixture((root, dir) => {
  const { git, remoteGit } = repository(root, dir);
  const head = git("rev-parse", "HEAD");
  publishTag({ root });
  assert.equal(git("cat-file", "-t", "refs/tags/v1.0.1"), "tag");
  assert.equal(remoteGit("rev-parse", "refs/tags/v1.0.1^{commit}"), head);
  assert.equal(remoteGit("rev-parse", "refs/heads/main"), head);
  const refs = remoteGit("show-ref");
  assert.throws(() => publishTag({ root }), /远端已有/);
  assert.equal(remoteGit("show-ref"), refs);
}));

test("dirty worktrees, untracked files, and unpublished commits stop before creating a tag", () => {
  for (const state of ["dirty", "untracked", "unpushed"]) withFixture((root, dir) => {
    const { git } = repository(root, dir);
    if (state === "dirty") write(root, "CHANGELOG.md", read(root, "CHANGELOG.md") + "\nchanged\n");
    else write(root, "new-file.md", "Test fixture\n");
    if (state === "unpushed") { git("add", "."); git("commit", "-m", "Unpublished fixture change"); }
    const refs = git("show-ref");
    assert.throws(() => publishTag({ root }), state === "unpushed" ? /先同步并推送/ : /未提交或未跟踪/);
    assert.equal(git("show-ref"), refs);
  });
});

test("conflicting local tags, newer remote tags, and detached HEAD stop publication", () => {
  for (const state of ["local-tag", "remote-tag", "detached"]) withFixture((root, dir) => {
    const { git } = repository(root, dir);
    if (state === "local-tag") git("tag", "v1.0.1", "HEAD~1");
    if (state === "remote-tag") { git("tag", "v1.0.2"); git("push", "--quiet", "origin", "refs/tags/v1.0.2"); }
    if (state === "detached") git("checkout", "--quiet", "--detach");
    const refs = git("show-ref");
    assert.throws(() => publishTag({ root }));
    assert.equal(git("show-ref"), refs);
  });
});

test("inconsistent metadata, incomplete notes, and missing committed workflow stop publication", () => {
  for (const state of ["version", "lock", "notes", "workflow"]) withFixture((root, dir) => {
    const { git } = repository(root, dir);
    if (state === "version") write(root, "windows-server/Cargo.toml", read(root, "windows-server/Cargo.toml").replace("1.0.1", "1.0.2"));
    if (state === "lock") write(root, "windows-server/Cargo.lock", read(root, "windows-server/Cargo.lock").replace("1.0.1", "0.9.0"));
    if (state === "notes") write(root, "CHANGELOG.md", read(root, "CHANGELOG.md").replace("- New feature.", ""));
    if (state === "workflow") fs.unlinkSync(path.join(root, ".github/workflows/release.yml"));
    git("add", ".");
    git("commit", "-m", "Invalid release fixture");
    git("push", "--quiet", "origin", "main");
    const refs = git("show-ref");
    const remoteRefs = git("ls-remote", "--refs", "origin");
    assert.throws(() => publishTag({ root }));
    assert.equal(git("show-ref"), refs);
    assert.equal(git("ls-remote", "--refs", "origin"), remoteRefs);
  });
});

test("failed pushes retain the checked local tag and allow a safe retry", () => withFixture((root, dir) => {
  const { git, remoteGit } = repository(root, dir);
  remoteGit("config", "receive.hideRefs", "refs/tags/v1.0.1");
  assert.throws(() => publishTag({ root }), /本地标签 v1.0.1 已保留/);
  assert.equal(git("rev-parse", "refs/tags/v1.0.1^{commit}"), git("rev-parse", "HEAD"));
  assert.ok(!remoteGit("tag", "--list").includes("v1.0.1"));
  remoteGit("config", "--unset", "receive.hideRefs");
  publishTag({ root });
  assert.equal(remoteGit("rev-parse", "refs/tags/v1.0.1^{commit}"), git("rev-parse", "HEAD"));
}));
