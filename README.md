# Atom (⚛)

```shell
 █████╗ ████████╗ ██████╗ ███╗   ███╗
██╔══██╗╚══██╔══╝██╔═══██╗████╗ ████║
███████║   ██║   ██║   ██║██╔████╔██║
██╔══██║   ██║   ██║   ██║██║╚██╔╝██║
██║  ██║   ██║   ╚██████╔╝██║ ╚═╝ ██║
╚═╝  ╚═╝   ╚═╝    ╚═════╝ ╚═╝     ╚═╝
```

[![release](https://github.com/appthreat/atom/actions/workflows/npm-release.yml/badge.svg)](https://github.com/appthreat/atom/actions/workflows/npm-release.yml)
![npm](https://img.shields.io/npm/dw/@appthreat/atom)
[![Discord](https://img.shields.io/badge/-Discord-lime?style=for-the-badge&logo=discord&logoColor=white&color=black)](https://discord.gg/tmmtjCEHNV)

## Installation

```shell
npm install @appthreat/atom
# sudo npm install -g @appthreat/atom
```

## Usage

```shell
Usage: atom [parsedeps] [options] [input]

  input                    source file or directory
  -o, --output <value>     output filename. Default app.⚛ or app.atom in windows
  -l, --language <value>   source language
  --withDataDeps           generate the atom with data-dependencies - defaults to `false`
Command: parsedeps

Misc
  -s, --slice              export intra-procedural slices as json
  --slice-outfile <value>  slice output filename
  --slice-depth <value>    the max depth to traverse the DDG for the data-flow slice (for `dataflow` mode) - defaults to 3
  -m, --mode <value>       the kind of slicing to perform - defaults to `dataflow`. Options: [dataflow, usages]
  --max-num-def <value>    maximum number of definitions in per-method data flow calculation. Default 2000
  --help                   display this help message
```

## Languages supported

- C/C++
- Java
- Jar
- Android APK
- JavaScript
- TypeScript
- Python

## License

Apache-2.0

## Developing / Contributing

Install Java 17 or 19

```shell
sbt scalafmt
sbt stage
```
