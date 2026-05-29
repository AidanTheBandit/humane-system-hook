#!/usr/bin/env node
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");

const MODULE_APKS = {
  hook: "hook/build/outputs/apk/release/hook-release.apk",
  injector: "injector/build/outputs/apk/release/injector-release.apk",
  server: "server/build/outputs/apk/release/server-release.apk",
};

function usage(exitCode = 1) {
  console.error(`Usage: scripts/dev-deploy.mjs --host <ip-or-url> [--no-build] [--apk <path> ...] [hook|injector|server ...]

Examples:
  scripts/dev-deploy.mjs --host 192.168.1.50 hook injector
  scripts/dev-deploy.mjs --host http://192.168.1.50:8080 --apk path/to/app.apk --no-build`);
  process.exit(exitCode);
}

function parseArgs(argv) {
  const modules = [];
  const apks = [];
  let host = null;
  let build = true;

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    switch (arg) {
      case "--host":
        host = argv[++i];
        if (!host) usage();
        break;
      case "--apk": {
        const apk = argv[++i];
        if (!apk) usage();
        apks.push(path.resolve(repoRoot, apk));
        break;
      }
      case "--no-build":
        build = false;
        break;
      case "--help":
      case "-h":
        usage(0);
        break;
      default:
        if (!Object.hasOwn(MODULE_APKS, arg)) {
          console.error(`Unknown module or option: ${arg}`);
          usage();
        }
        modules.push(arg);
        break;
    }
  }

  if (!host) usage();
  if (modules.length === 0 && apks.length === 0) usage();

  return { host: normalizeHost(host), build, modules, apks };
}

function normalizeHost(host) {
  if (host.startsWith("http://") || host.startsWith("https://")) {
    return host.replace(/\/$/, "");
  }
  return `http://${host}:8080`;
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: repoRoot,
      stdio: "inherit",
      ...options,
    });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} ${args.join(" ")} exited ${code}`));
    });
  });
}

function safeUploadName(index, apkPath) {
  const parsed = path.parse(path.basename(apkPath));
  const base = parsed.name.replace(/[^A-Za-z0-9._-]/g, "_");
  const ext = (parsed.ext || ".apk").replace(/[^A-Za-z0-9._-]/g, "_");
  return `${Date.now()}-${index}-${base}${ext}`;
}

async function requestInstall(host, apkPaths, filenames) {
  const form = new FormData();
  for (let i = 0; i < apkPaths.length; i++) {
    const bytes = await readFile(apkPaths[i]);
    const blob = new Blob([bytes], {
      type: "application/vnd.android.package-archive",
    });
    form.append("apk", blob, filenames[i]);
  }

  const response = await fetch(`${host}/api/dev/install`, {
    method: "POST",
    body: form,
  });

  if (!response.ok) {
    throw new Error(
      `install request failed (${response.status}): ${await response.text()}`,
    );
  }

  return response.json();
}

async function waitForHealth(host, timeoutMs = 120_000, intervalMs = 2_000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const response = await fetch(`${host}/api/health`);
      if (response.ok) return;
    } catch {
      // Expected while system_server/server restarts.
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`timed out waiting for ${host}/api/health`);
}

async function main() {
  const { host, build, modules, apks } = parseArgs(process.argv.slice(2));

  if (build && modules.length > 0) {
    const tasks = modules.map((module) => `:${module}:assembleRelease`);
    console.log(`[1/4] Building ${modules.join(", ")}...`);
    await run("./gradlew", tasks);
  } else {
    console.log("[1/4] Skipping build");
  }

  const moduleApks = modules.map((module) =>
    path.join(repoRoot, MODULE_APKS[module]),
  );
  const apkPaths = [...moduleApks, ...apks];
  for (const apkPath of apkPaths) {
    if (!existsSync(apkPath)) {
      throw new Error(`APK not found: ${apkPath}`);
    }
  }

  const uploadNames = apkPaths.map((apkPath, index) =>
    safeUploadName(index, apkPath),
  );

  console.log(
    `[2/4] Uploading and installing ${apkPaths.length} APK(s) to ${host}...`,
  );
  for (let i = 0; i < apkPaths.length; i++) {
    console.log(`      ${path.basename(apkPaths[i])} -> ${uploadNames[i]}`);
  }

  try {
    const result = await requestInstall(host, apkPaths, uploadNames);
    console.log(`      accepted: ${JSON.stringify(result)}`);
  } catch (error) {
    console.warn(`      install request connection failed: ${error.message}`);
    console.warn(
      "      continuing to health polling in case restart already began",
    );
  }

  console.log("[3/4] Waiting for server health...");
  await waitForHealth(host);

  console.log("[4/4] Deploy complete");
}

main().catch((error) => {
  console.error(`Error: ${error.message}`);
  process.exit(1);
});
