#!/usr/bin/env node

const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");
const isWin = require("os").platform() === "win32";
const LOG4J_CONFIG = path.join(__dirname, "plugins", "log4j2.xml");
const ATOM_HOME = path.join(__dirname, "plugins", "atom-1.0.0");
const APP_LIB_DIR = path.join(ATOM_HOME, "lib");
const freeMemoryGB = Math.floor(os.freemem() / 1024 / 1024 / 1024);
const JAVA_OPTS = `${process.env.JAVA_OPTS || ""} -Xms${Math.round(
  Math.floor(freeMemoryGB / 2)
)}G -Xmx${freeMemoryGB}G -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent -XX:+ParallelRefProcEnabled -XX:+UseStringDeduplication -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:+UnlockDiagnosticVMOptions -XX:G1SummarizeRSetStatsPeriod=1`;
const APP_MAIN_CLASS = "io.appthreat.atom.Atom";
let APP_CLASSPATH = path.join(
  APP_LIB_DIR,
  "io.appthreat.atom-1.0.0-classpath.jar"
);
let JAVACMD = "java";
if (process.env.JAVA_HOME) {
  JAVACMD = path.join(
    process.env.JAVA_HOME,
    "bin",
    "java" + (isWin ? ".exe" : "")
  );
}

const atomLibs = [APP_CLASSPATH];
const argv = process.argv.slice(2);
let args = JAVA_OPTS.trim()
  .split(" ")
  .concat([
    "-cp",
    atomLibs.join(path.delimiter),
    `-Dlog4j.configurationFile=${LOG4J_CONFIG}`,
    APP_MAIN_CLASS,
    ...argv
  ]);
const env = {
  ...process.env,
  ATOM_HOME
};
const cwd = process.env.ATOM_CWD || process.cwd();
spawnSync(JAVACMD, args, {
  encoding: "utf-8",
  env,
  cwd,
  stdio: "inherit",
  stderr: "inherit",
  timeout: undefined
});
