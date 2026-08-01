#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const sharp = require("sharp");

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || value == null) {
      throw new Error(`invalid argument near ${key ?? "end of command"}`);
    }
    result[key.slice(2)] = value;
  }
  for (const required of ["manifest", "raw-root", "output-root"]) {
    if (!result[required]) throw new Error(`missing --${required}`);
  }
  return result;
}

async function atomicView(inputPath, outputPath, fit) {
  const temporaryPath = `${outputPath}.${process.pid}.tmp`;
  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await sharp(inputPath, {
    failOn: "error",
    limitInputPixels: false,
    sequentialRead: true,
  })
    .flatten({ background: "#ffffff" })
    .resize({
      width: 224,
      height: 224,
      fit,
      position: "centre",
      background: "#ffffff",
      kernel: sharp.kernel.lanczos3,
    })
    .png({ compressionLevel: 6, adaptiveFiltering: true })
    .toFile(temporaryPath);
  await fs.rename(temporaryPath, outputPath);
}

async function isComplete(outputPath) {
  try {
    const metadata = await sharp(outputPath).metadata();
    return metadata.format === "png" && metadata.width === 224 && metadata.height === 224;
  } catch {
    return false;
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const manifestText = await fs.readFile(path.resolve(args.manifest), "utf8");
  const records = manifestText
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line));
  const rawRoot = path.resolve(args["raw-root"]);
  const outputRoot = path.resolve(args["output-root"]);
  const concurrency = Math.max(1, Number.parseInt(args.concurrency ?? "2", 10));
  const limit = Math.max(0, Number.parseInt(args.limit ?? "0", 10));
  const selected = limit > 0 ? records.slice(0, limit) : records;
  const viewRecords = new Array(selected.length);
  let next = 0;
  let completed = 0;
  let reused = 0;

  sharp.concurrency(concurrency);
  async function worker() {
    while (true) {
      const index = next++;
      if (index >= selected.length) return;
      const record = selected[index];
      const source = path.join(rawRoot, ...record.relative_path.split("/"));
      const directory = path.join(outputRoot, record.split);
      const globalPath = path.join(directory, `${record.roi_id}__global.png`);
      const centerPath = path.join(directory, `${record.roi_id}__center.png`);
      const globalReady = await isComplete(globalPath);
      const centerReady = await isComplete(centerPath);
      if (!globalReady) await atomicView(source, globalPath, "contain");
      if (!centerReady) await atomicView(source, centerPath, "cover");
      if (globalReady && centerReady) reused += 1;
      viewRecords[index] = {
        roi_id: record.roi_id,
        slide_id: record.slide_id,
        patient_id: record.patient_id,
        split: record.split,
        label: record.label,
        source_sha256: record.sha256,
        global_path: path.relative(outputRoot, globalPath).replaceAll("\\", "/"),
        center_path: path.relative(outputRoot, centerPath).replaceAll("\\", "/"),
      };
      completed += 1;
      if (completed % 100 === 0 || completed === selected.length) {
        process.stdout.write(
          `${JSON.stringify({ completed, total: selected.length, reused })}\n`,
        );
      }
    }
  }

  await fs.mkdir(outputRoot, { recursive: true });
  await Promise.all(Array.from({ length: concurrency }, () => worker()));
  const manifestPath = path.join(outputRoot, "views.jsonl");
  const temporaryManifest = `${manifestPath}.${process.pid}.tmp`;
  await fs.writeFile(
    temporaryManifest,
    `${viewRecords.map((record) => JSON.stringify(record)).join("\n")}\n`,
    "utf8",
  );
  await fs.rename(temporaryManifest, manifestPath);
  await fs.writeFile(
    path.join(outputRoot, "view_config.json"),
    `${JSON.stringify(
      {
        schema_version: 1,
        source_manifest: path.resolve(args.manifest),
        raw_root: rawRoot,
        records: selected.length,
        views_per_record: 2,
        image_size: 224,
        global_fit: "contain",
        center_fit: "cover",
        format: "lossless PNG",
      },
      null,
      2,
    )}\n`,
    "utf8",
  );
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error.message}\n`);
  process.exitCode = 1;
});
