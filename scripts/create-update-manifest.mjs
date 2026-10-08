import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

export const readVersions = (root = ".") => {
  const gradle = fs.readFileSync(path.join(root, "android-app/app/build.gradle.kts"), "utf8");
  const code = gradle.match(/\bversionCode\s*=\s*(\d+)/);
  const name = gradle.match(/\bversionName\s*=\s*"([0-9A-Za-z.+-]+)"/);
  if (!code || !name) throw new Error("Android versionName/versionCode is missing");
  const cargo = fs.readFileSync(path.join(root, "windows-server/Cargo.toml"), "utf8");
  const match = cargo.match(/^version\s*=\s*"([0-9A-Za-z.+-]+)"/m);
  if (!match) throw new Error("Windows package version is missing");
  return { androidCode: Number(code[1]), androidName: name[1], windows: match[1] };
};

export const validateVersions = (version, versions) => {
  if (!/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(version)) {
    throw new Error("Only stable major.minor.patch releases are supported");
  }
  if (versions.androidName !== version || versions.windows !== version) {
    throw new Error(`Release ${version}, Android ${versions.androidName}, and Windows ${versions.windows} must match`);
  }
  if (!Number.isInteger(versions.androidCode) || versions.androidCode < 1 || versions.androidCode > 2100000000) {
    throw new Error("Invalid Android versionCode");
  }
};

export const releaseNotesByLanguage = (version, changelog = fs.readFileSync("CHANGELOG.md", "utf8")) => {
  const sections = changelog.split(/^##\s+/m).slice(1);
  const section = sections.find((value) => value.split(/\r?\n/, 1)[0].trim() === version);
  if (!section) throw new Error(`CHANGELOG.md is missing the ${version} section`);
  if (!section.includes("\n")) throw new Error(`CHANGELOG.md has no release notes for ${version}`);
  const notes = section.slice(section.indexOf("\n") + 1).trim();
  if (!notes) throw new Error(`CHANGELOG.md has no release notes for ${version}`);
  const languages = notes.split(/^###\s+/m).slice(1);
  const languageNotes = (heading) => {
    const matches = languages.filter((value) => value.split(/\r?\n/, 1)[0].trim() === heading);
    if (matches.length !== 1) throw new Error(`CHANGELOG.md must contain one ${heading} section for ${version}`);
    const value = matches[0];
    const content = value.includes("\n") ? value.slice(value.indexOf("\n") + 1).trim() : "";
    if (!content) throw new Error(`CHANGELOG.md has no ${heading} release notes for ${version}`);
    return content.replace(/\r\n/g, "\n");
  };
  return { zh: languageNotes("中文"), en: languageNotes("English") };
};

export const releaseNotes = (version, changelog) => {
  const notes = releaseNotesByLanguage(version, changelog);
  return `## 中文更新说明\n\n${notes.zh}\n\n## English release notes\n\n${notes.en}`;
};

const sha256 = (file) => {
  const hash = crypto.createHash("sha256");
  hash.update(fs.readFileSync(file));
  return hash.digest("hex");
};

export const createManifest = ({ version, repository, androidAsset, windowsAsset, versions, notes }) => {
  validateVersions(version, versions);
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository)) throw new Error("Invalid repository");
  if (path.basename(androidAsset) !== `phone-mouse-android-${version}.apk` ||
      path.basename(windowsAsset) !== `phone-mouse-windows-${version}.exe`) {
    throw new Error("Release asset filenames must match the version");
  }
  const assetUrl = (file) => {
    const name = path.basename(file);
    return `https://github.com/${repository}/releases/download/v${version}/${encodeURIComponent(name)}`;
  };

  return {
    schema: 1,
    version,
    release_page: `https://github.com/${repository}/releases/tag/v${version}`,
    notes,
    android: {
      version_name: version,
      version_code: versions.androidCode,
      url: assetUrl(androidAsset),
      sha256: sha256(androidAsset),
    },
    windows: {
      version,
      url: assetUrl(windowsAsset),
      sha256: sha256(windowsAsset),
    },
  };
};

const main = () => {
  const args = process.argv.slice(2);
  if (args[0] === "--check") {
    const tag = args[1];
    if (!tag?.startsWith("v")) throw new Error("Release tag must start with v");
    validateVersions(tag.slice(1), readVersions());
    releaseNotes(tag.slice(1));
    console.log(`Release ${tag} versions and changelog are valid`);
    return;
  }
  if (args[0] === "--notes") {
    const [, version, output] = args;
    fs.writeFileSync(output, `# 手机键鼠 / Phone Mouse v${version}\n\n${releaseNotes(version)}\n`, "utf8");
    return;
  }
  const [version, repository, androidAsset, windowsAsset, output] = args;
  if (args.length !== 5 || args.some((value) => !value)) {
    throw new Error("Usage: node scripts/create-update-manifest.mjs <version> <owner/repo> <android.apk> <windows.exe> <output.json>");
  }
  const manifest = createManifest({ version, repository, androidAsset, windowsAsset,
    versions: readVersions(), notes: releaseNotesByLanguage(version).zh });
  fs.writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  console.log(`Wrote ${output} for v${version}`);
};

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main();
