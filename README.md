<div align="center">

<p>
  <img src="icon.png" alt="Hyperglide" width="192">
</p>

<h3>Hyperglide</h3>

<p><i>A Meteor Client add-on focused on utility modules for the 2b2t server.</i></p>

[![License][shield-repo-license]][repo-license]
[![Latest Release][shield-repo-latest]][repo-latest]
[![Stars][shield-repo-stargazers]][repo-stargazers]
[![Downloads][shield-repo-releases]][repo-releases]
[![Forks][shield-repo-forks]][repo-forks]

<br>

[![Join CoreBuilders Discord](https://invidget.switchblade.xyz/corebuilders)](https://discord.gg/corebuilders)

</div>

## 📖 About

Add-on for [Meteor Client][meteor] targeting the 2b2t anarchy server, with practical utility modules for automation.

## 📦 Requirements

- **[Minecraft 1.21.4][minecraft]**
- **[Fabric Loader][fabric]**
- **[Meteor Client][meteor]**
- **[Baritone][baritone]**

## 🧩 Modules

- **Air Place** - Allows blocks to be placed in the air without requiring the adjacent surface.
- **Auto Web** - Places cobwebs around selected entities with optional movement prediction.
- **Bounce Fly** - Uses elytra bouncing for fast highway travel with optional obstacle passing.
- **Easy Access** - Opens hidden containers and supported interactive entities within range.
- **Fast Portal** - Builds and lights nether portals near the player using minimal obsidian.
- **FD3 Crafter** - Strictly crafts FD3 firework rockets from available inventory ingredients.
- **Mining Tweaks** - Enables packet mining, including fast remine and double-break support.
- **Scaffolding** - Places selected blocks beneath and ahead of the player for safer bridging.

## 📁 Project Structure

```text
Hyperglide/
├── gradle/                         # Gradle wrapper files
├── libs/                           # Local dependencies
├── src/
│   └── main/
│       ├── java/dev/arkieee/hyperglide/
│       │   ├── mixin/              # Mixin classes
│       │   ├── modules/            # Meteor modules
│       │   └── Hyperglide.java   # Main entry point
│       └── resources/
│           ├── assets/hyperglide/
│           │   └── icon.png
│           ├── fabric.mod.json
│           └── mixins.json
├── build.gradle
├── gradle.properties
├── settings.gradle
├── gradlew
├── gradlew.bat
├── icon.png
├── LICENSE
└── README.md
```

## 🛠️ Building from Source

### Prerequisites

- **[JDK 21 or newer][java]**
- **[Git][git]**

### Windows

```powershell
git clone https://github.com/Ark223/Hyperglide.git
cd Hyperglide
.\gradlew.bat clean build
```

### Linux and macOS

```bash
git clone https://github.com/Ark223/Hyperglide.git
cd Hyperglide
./gradlew clean build
```

The compiled add-on JAR will be located in:

```text
build/libs/
```

## 🐛 Issues and Suggestions

Found a bug or have an idea?

- [Report a bug][repo-issues]
- [Suggest a feature][repo-issues]
- [Open a pull request][repo-pulls]

## 📄 License

This add-on is licensed under the [GNU General Public License v3.0][repo-license].

[java]: https://www.azul.com/downloads/
[git]: https://git-scm.com/downloads/

[minecraft]: https://prismlauncher.org/download/
[fabric]: https://fabricmc.net/use/installer/
[meteor]: https://meteorclient.com/
[baritone]: https://maven.meteordev.org/#/snapshots/meteordevelopment/baritone

[shield-repo-license]: https://img.shields.io/github/license/Ark223/Hyperglide?style=flat&labelColor=30363d&color=2ea44f
[repo-license]: https://github.com/Ark223/Hyperglide/blob/main/LICENSE

[shield-repo-latest]: https://img.shields.io/github/v/release/Ark223/Hyperglide?display_name=tag&label=latest&labelColor=30363d&color=1f6feb
[repo-latest]: https://github.com/Ark223/Hyperglide/releases/latest

[shield-repo-stargazers]: https://img.shields.io/github/stars/Ark223/Hyperglide?style=flat&labelColor=30363d&color=bf8700&cacheSeconds=300
[repo-stargazers]: https://github.com/Ark223/Hyperglide/stargazers

[shield-repo-releases]: https://img.shields.io/github/downloads/Ark223/Hyperglide/total?labelColor=30363d&color=8957e5&cacheSeconds=300
[repo-releases]: https://github.com/Ark223/Hyperglide/releases

[shield-repo-forks]: https://img.shields.io/github/forks/Ark223/Hyperglide?style=flat&labelColor=30363d&color=6366f1&cacheSeconds=300
[repo-forks]: https://github.com/Ark223/Hyperglide/forks

[repo-issues]: https://github.com/Ark223/Hyperglide/issues
[repo-pulls]: https://github.com/Ark223/Hyperglide/pulls
