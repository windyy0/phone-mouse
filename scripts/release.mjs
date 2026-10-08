import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";
import { readVersions, validateVersions, releaseNotesByLanguage } from "./create-update-manifest.mjs";

const VERSION_FILES = [
  "android-app/app/build.gradle.kts", "windows-server/Cargo.toml",
  "windows-server/Cargo.lock", "CHANGELOG.md",
];
const RELEASE_FILES = [
  ...VERSION_FILES, ".github/workflows/release.yml",
  "scripts/create-update-manifest.mjs", "scripts/release.mjs",
];
const STABLE_VERSION = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;

const compareVersions = (left, right) => {
  const a = left.split(".").map(BigInt);
  const b = right.split(".").map(BigInt);
  for (let i = 0; i < 3; i++) {
    if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
  }
  return 0;
};

const replaceOnce = (text, pattern, replacement, description) => {
  if ([...text.matchAll(pattern)].length !== 1) throw new Error(`${description} 必须存在且唯一。`);
  return text.replace(pattern, replacement);
};

const lockVersion = (text, packageName, current, next) => {
  let found = 0;
  const blocks = text.split(/(?=^\[\[package\]\])/m).map((block) => {
    if (block.match(/^name\s*=\s*"([^"]+)"/m)?.[1] !== packageName || /^source\s*=/m.test(block)) return block;
    found++;
    if (block.match(/^version\s*=\s*"([^"]+)"/m)?.[1] !== current) {
      throw new Error("Cargo.lock 与 Windows 版本不一致，请先同步锁文件。");
    }
    return replaceOnce(block, /^(version\s*=\s*")[^"]+(".*)$/gm,
      (_, prefix, suffix) => `${prefix}${next}${suffix}`, "Cargo.lock 项目版本");
  });
  if (found !== 1) throw new Error("Cargo.lock 中必须有唯一的项目包。");
  return blocks.join("");
};

const headings = (text) => [...text.matchAll(/^##[ \t]+([^\r\n]+)\r?$/gm)].map((match) => match[1].trim());

export const prepareVersion = (version, root = ".") => {
  const current = readVersions(root);
  validateVersions(current.androidName, current);
  const next = { androidName: version, windows: version, androidCode: current.androidCode + 1 };
  validateVersions(version, next);
  if (compareVersions(version, current.androidName) <= 0) throw new Error("新版本号必须大于当前版本。");

  const originals = Object.fromEntries(VERSION_FILES.map((file) => [file, fs.readFileSync(path.join(root, file), "utf8")]));
  const [gradle, cargo, lock, changelog] = VERSION_FILES.map((file) => originals[file]);
  const sections = headings(changelog);
  if (sections.filter((heading) => heading === "未发布").length !== 1 || sections.includes(version)) {
    throw new Error("更新记录必须有唯一的「未发布」栏目，且新版本尚未存在。");
  }
  releaseNotesByLanguage("未发布", changelog);
  const packageName = cargo.match(/^name\s*=\s*"([^"]+)"/m)?.[1];
  if (!packageName) throw new Error("缺少 Windows 项目名称。");
  const newline = changelog.includes("\r\n") ? "\r\n" : "\n";
  const newHeading = ["## 未发布", "", "### 中文", "", "### English", "", `## ${version}`, ""].join(newline);
  const updates = [
    replaceOnce(replaceOnce(gradle, /(\bversionName\s*=\s*")[^"]+(".*)/g,
      (_, prefix, suffix) => `${prefix}${version}${suffix}`, "Android versionName"),
      /(\bversionCode\s*=\s*)\d+/g, (_, prefix) => `${prefix}${next.androidCode}`, "Android versionCode"),
    replaceOnce(cargo, /^(version\s*=\s*")[^"]+(".*)$/gm,
      (_, prefix, suffix) => `${prefix}${version}${suffix}`, "Windows 版本"),
    lockVersion(lock, packageName, current.windows, version),
    changelog.replace(/^##[ \t]+未发布[ \t]*(?:\r?\n|$)/m, newHeading),
  ];
  const written = [];
  try {
    VERSION_FILES.forEach((file, i) => {
      written.push(file);
      fs.writeFileSync(path.join(root, file), updates[i], "utf8");
    });
  } catch (error) {
    for (const file of written.reverse()) fs.writeFileSync(path.join(root, file), originals[file], "utf8");
    throw error;
  }
  return { version, versionCode: next.androidCode };
};

const gitResult = (root, args) => spawnSync("git", args, { cwd: root, encoding: "utf8", windowsHide: true });
const git = (root, args) => {
  const result = gitResult(root, args);
  if (result.error || result.status !== 0) {
    throw new Error(`Git ${args[0]} 失败：${result.error?.message || result.stderr.trim()}`);
  }
  return result.stdout.trim();
};
const requireClean = (root) => {
  if (git(root, ["status", "--porcelain", "--untracked-files=all"])) {
    throw new Error("工作区有未提交或未跟踪的文件，请先检查并提交代码。");
  }
};

export const publishTag = ({ root = ".", dryRun = false } = {}) => {
  root = fs.realpathSync(root);
  if (fs.realpathSync(git(root, ["rev-parse", "--show-toplevel"])) !== root) {
    throw new Error("请在项目根目录运行发布命令。");
  }
  requireClean(root);
  const branchResult = gitResult(root, ["symbolic-ref", "--quiet", "--short", "HEAD"]);
  if (branchResult.status !== 0) throw new Error("请先切换到要发布的分支，当前处于 detached HEAD。");
  const branch = branchResult.stdout.trim();
  const head = git(root, ["rev-parse", "HEAD"]);
  const versions = readVersions(root);
  const version = versions.androidName;
  validateVersions(version, versions);
  const changelog = fs.readFileSync(path.join(root, "CHANGELOG.md"), "utf8");
  if (headings(changelog).filter((heading) => heading === version).length !== 1) {
    throw new Error("更新记录必须有唯一的当前版本栏目。");
  }
  releaseNotesByLanguage(version, changelog);
  const cargo = fs.readFileSync(path.join(root, "windows-server/Cargo.toml"), "utf8");
  lockVersion(fs.readFileSync(path.join(root, "windows-server/Cargo.lock"), "utf8"),
    cargo.match(/^name\s*=\s*"([^"]+)"/m)?.[1], versions.windows, versions.windows);
  git(root, ["ls-files", "--error-unmatch", "--", ...RELEASE_FILES]);
  const tag = `v${version}`;
  const localTag = gitResult(root, ["show-ref", "--verify", "--quiet", `refs/tags/${tag}`]);
  if (localTag.status !== 0 && localTag.status !== 1) throw new Error("无法检查本地标签。");
  const existsLocally = localTag.status === 0;
  if (existsLocally && git(root, ["rev-parse", `refs/tags/${tag}^{commit}`]) !== head) {
    throw new Error(`本地标签 ${tag} 指向其他提交，已停止发布。`);
  }
  const remoteRefs = git(root, ["ls-remote", "--refs", "origin", `refs/heads/${branch}`, "refs/tags/v*"])
    .split(/\r?\n/).filter(Boolean).map((line) => line.split(/\s+/));
  if (!remoteRefs.some(([commit, ref]) => ref === `refs/heads/${branch}` && commit === head)) {
    throw new Error("当前提交与 origin 分支不同，请先同步并推送代码。");
  }
  for (const [, ref] of remoteRefs) {
    if (!ref.startsWith("refs/tags/v")) continue;
    const existing = ref.slice("refs/tags/v".length);
    if (STABLE_VERSION.test(existing) && compareVersions(existing, version) >= 0) {
      throw new Error(`远端已有 ${ref.slice("refs/tags/".length)}，新发布版本必须更高，标签不会被覆盖。`);
    }
  }
  requireClean(root);
  if (git(root, ["rev-parse", "HEAD"]) !== head) throw new Error("检查期间提交发生变化，请重新运行发布命令。");
  if (!dryRun) {
    if (!existsLocally) git(root, ["tag", "-a", tag, "-m", `Release ${tag}`, head]);
    try {
      git(root, ["push", "origin", `refs/tags/${tag}:refs/tags/${tag}`]);
    } catch (error) {
      throw new Error(`${error.message}\n本地标签 ${tag} 已保留；修复推送问题后可重新运行 just release。`);
    }
  }
  return { version, tag, branch, head };
};

const main = () => {
  const [command, ...args] = process.argv.slice(2);
  if (command === "version" && args.length === 1) {
    const result = prepareVersion(args[0]);
    console.log(`已更新为 v${result.version}，Android versionCode 为 ${result.versionCode}。`);
    console.log("请检查更新说明、提交并推送代码，然后运行 just release。");
  } else if (command === "release" && (args.length === 0 || (args.length === 1 && args[0] === "--dry-run"))) {
    const dryRun = args[0] === "--dry-run";
    const result = publishTag({ dryRun });
    console.log(dryRun ? `检查通过：${result.branch} → ${result.tag}，尚未创建或推送标签。`
      : `已推送 ${result.tag}，GitHub Actions 将自动构建并发布。`);
  } else {
    throw new Error("用法：node scripts/release.mjs version <版本号> | release [--dry-run]");
  }
};

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
