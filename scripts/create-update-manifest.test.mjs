import assert from "node:assert/strict";
import { test } from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { createManifest, validateVersions, releaseNotes, releaseNotesByLanguage } from "./create-update-manifest.mjs";

const versions = { androidCode: 4, androidName: "1.0.1", windows: "1.0.1" };

test("release version must agree across tag, Android, and Windows", () => {
  validateVersions("1.0.1", versions);
  for (const version of ["1.0.0", "1.0", "01.0.1", "1.0.1-beta"]) {
    assert.throws(() => validateVersions(version, versions));
  }
  assert.throws(() => validateVersions("1.0.1", { ...versions, androidCode: 0 }));
  assert.throws(() => validateVersions("1.0.1", { ...versions, windows: "1.0.0" }));
});

test("bilingual notes contain only the matching version and require both languages", () => {
  const changelog = "# 更新记录\r\n\r\n## 1.0.1\r\n\r\n### 中文\r\n\r\n- 新功能。\r\n\r\n### English\r\n\r\n- New feature.\r\n\r\n## 1.0.0\r\n\r\n### 中文\r\n\r\n- 首次发布。\r\n\r\n### English\r\n\r\n- Initial release.\r\n";
  assert.deepEqual(releaseNotesByLanguage("1.0.1", changelog), { zh: "- 新功能。", en: "- New feature." });
  assert.equal(releaseNotes("1.0.1", changelog), "## 中文更新说明\n\n- 新功能。\n\n## English release notes\n\n- New feature.");
  assert.throws(() => releaseNotes("1.0.2", changelog));
  assert.throws(() => releaseNotes("1.0.1", "## 1.0.1"));
  for (const notes of ["### 中文\n- 新功能。", "### English\n- New feature.",
    "### 中文\n\n### English\n- New feature.", "### 中文\n- 新功能。\n### English\n",
    "### 中文\n- 新功能。\n### 中文\n- 重复。\n### English\n- New feature."]) {
    assert.throws(() => releaseNotes("1.0.1", `## 1.0.1\n\n${notes}`));
  }
});

test("manifest links to immutable versioned assets with correct checksums", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phone-mouse-manifest-"));
  try {
    const androidAsset = path.join(dir, "phone-mouse-android-1.0.1.apk");
    const windowsAsset = path.join(dir, "phone-mouse-windows-1.0.1.exe");
    fs.writeFileSync(androidAsset, "android test fixture");
    fs.writeFileSync(windowsAsset, "windows test fixture");
    const result = createManifest({ version: "1.0.1", repository: "windyy0/phone-mouse",
      androidAsset, windowsAsset, versions, notes: "更新说明" });
    assert.equal(result.schema, 1);
    assert.equal(result.android.version_code, 4);
    assert.equal(result.android.url, "https://github.com/windyy0/phone-mouse/releases/download/v1.0.1/phone-mouse-android-1.0.1.apk");
    assert.equal(result.windows.url, "https://github.com/windyy0/phone-mouse/releases/download/v1.0.1/phone-mouse-windows-1.0.1.exe");
    assert.equal(result.windows.sha256, crypto.createHash("sha256").update("windows test fixture").digest("hex"));
    assert.equal(result.notes, "更新说明");
    assert.throws(() => createManifest({ version: "1.0.1", repository: "windyy0/phone-mouse",
      androidAsset: path.join(dir, "wrong.apk"), windowsAsset, versions, notes: "" }));
    assert.throws(() => createManifest({ version: "1.0.1", repository: "windyy0/phone-mouse",
      androidAsset, windowsAsset: path.join(dir, "phone-mouse-windows-1.0.1.apk"), versions, notes: "" }));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("CLI generates bilingual release Markdown and Chinese client notes", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phone-mouse-release-cli-"));
  const script = fileURLToPath(new URL("./create-update-manifest.mjs", import.meta.url));
  try {
    fs.mkdirSync(path.join(dir, "android-app/app"), { recursive: true });
    fs.mkdirSync(path.join(dir, "windows-server"));
    fs.writeFileSync(path.join(dir, "android-app/app/build.gradle.kts"), 'versionCode = 4\nversionName = "1.0.1"\n');
    fs.writeFileSync(path.join(dir, "windows-server/Cargo.toml"), 'version = "1.0.1"\n');
    fs.writeFileSync(path.join(dir, "CHANGELOG.md"), "## 1.0.1\n\n### 中文\n\n- 新功能。\n\n### English\n\n- New feature.\n");
    const androidAsset = path.join(dir, "phone-mouse-android-1.0.1.apk");
    const windowsAsset = path.join(dir, "phone-mouse-windows-1.0.1.exe");
    fs.writeFileSync(androidAsset, "android fixture");
    fs.writeFileSync(windowsAsset, "windows fixture");
    const notesFile = path.join(dir, "release-notes.md");
    const manifestFile = path.join(dir, "latest.json");
    const run = (...args) => execFileSync(process.execPath, [script, ...args], { cwd: dir });
    run("--check", "v1.0.1");
    run("--notes", "1.0.1", notesFile);
    run("1.0.1", "windyy0/phone-mouse", androidAsset, windowsAsset, manifestFile);
    const markdown = fs.readFileSync(notesFile, "utf8");
    assert.ok(markdown.startsWith("# 手机键鼠 / Phone Mouse v1.0.1\n"));
    assert.ok(markdown.includes("## 中文更新说明\n\n- 新功能。"));
    assert.ok(markdown.includes("## English release notes\n\n- New feature."));
    assert.equal(JSON.parse(fs.readFileSync(manifestFile, "utf8")).notes, "- 新功能。");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
