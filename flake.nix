{
  description = "agentbox: opencode + claude-code + opkg + jCodeMunch + Coursier/sbt on JDK 25";

  inputs.nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };

      name = "agentbox";
      tag = "latest";
      home = "/home/dev";
      state = "/opt/agent";

      jdk = pkgs.jdk25_headless;

      devbox = pkgs.writeShellApplication {
        name = "devbox";
        text = ''
          case "''${1:-}" in
            readme) exec cat /share/doc/${name}/README.md ;;
            *)
              echo "usage: devbox readme" >&2
              exit 1
              ;;
          esac
        '';
      };

      bootstrap = pkgs.writeShellApplication {
        name = "bootstrap";
        text = ''
          run_as_user() {
            if [ $# -eq 0 ] || [ "''${1##*/}" = "bash" ]; then
              echo "agentbox — docs: devbox readme" >&2
              echo "dashboard:   http://localhost:8080  (sbt dashboardServe in /work)" >&2
            fi

            mkdir -p "${state}"/{bin,cache,state,uv,npm,npm-cache}

            # sbt itself comes from nixpkgs — the cs-installed one was a shim
            # into ~/.cache that died with the container; clear stale copies
            rm -f "${state}/bin/sbt"
            if [ ! -x "${state}/bin/scalafmt" ]; then
              echo "==> cs install scala scalac scalafmt" >&2
              cs install --quiet scala scalac scalafmt
            fi

            if [ ! -x "${state}/bin/jcodemunch-mcp" ]; then
              echo "==> uv tool install jcodemunch-mcp" >&2
              uv tool install jcodemunch-mcp
            fi

            if [ ! -x "${state}/bin/opkg" ]; then
              echo "==> npm install -g opkg" >&2
              npm install -g --silent opkg
            fi

            exec "$@"
          }

          # Entrypoint starts as root: register the host uid/gid so tools get a
          # passwd entry (Node's os.userInfo needs one), take ownership of the
          # writable state dir, then drop to the host user for everything else.
          if [ "$(id -u)" = "0" ] && [ -n "''${HOST_UID:-}" ]; then
            HOST_GID="''${HOST_GID:-$HOST_UID}"
            grep -qs "^[^:]*:[^:]*:''${HOST_UID}:" /etc/passwd || \
              echo "dev:x:''${HOST_UID}:''${HOST_GID}::${home}:/bin/bash" >> /etc/passwd
            grep -qs "^[^:]*:[^:]*:''${HOST_GID}:" /etc/group || \
              echo "dev:x:''${HOST_GID}:" >> /etc/group
            mkdir -p "${state}"
            chown -R "''${HOST_UID}:''${HOST_GID}" "${state}"
            chown "''${HOST_UID}:''${HOST_GID}" "$HOME"
            exec su-exec "''${HOST_UID}:''${HOST_GID}" "$0" "$@"
          fi

          if [ "$(id -u)" = "0" ]; then
            echo "error: would run as root — start via the agentbox wrapper" >&2
            echo "(it passes HOST_UID/HOST_GID to map your host identity)" >&2
            exit 1
          fi

          run_as_user "$@"
        '';
      };

      image = pkgs.dockerTools.buildLayeredImage {
        inherit name tag;

        contents = with pkgs; [
          bashInteractive
          coreutils
          gnugrep
          gnused
          gnutar
          gzip
          findutils
          which
          less
          curl
          git
          openssh
          ripgrep
          fd
          jq
          gh
          opencode
          claude-code
          nodejs_22 # runtime for opkg
          coursier
          sbt
          jdk
          uv
          python3 # runtime for jcodemunch-mcp
          su-exec
          devbox
          readme
          dockerTools.fakeNss
          dockerTools.caCertificates
          context7-mcp
        ];

        extraCommands = ''
          mkdir -p tmp work opt/agent \
                   home/dev/.config/opencode \
                   home/dev/.local/share/opencode \
                   home/dev/.local/state \
                   home/dev/.openpackage \
                   home/dev/.claude
          touch home/dev/.claude.json
          chmod 1777 tmp
          chmod -R 0777 home/dev work opt/agent
          # cs/uv/npm-installed launchers use #!/usr/bin/env; the image only
          # populates /bin
          mkdir -p usr
          ln -s /bin usr/bin
        '';

        config = {
          Entrypoint = [ "${bootstrap}/bin/bootstrap" ];
          Cmd = [ "${pkgs.bashInteractive}/bin/bash" ];
          WorkingDir = "/work";
          Env = [
            "HOME=${home}"
            "PATH=${state}/bin:/bin:/usr/bin"
            "JAVA_HOME=${jdk}"
            "COURSIER_BIN_DIR=${state}/bin"
            "COURSIER_CACHE=${state}/cache"
            "UV_TOOL_DIR=${state}/uv"
            "UV_TOOL_BIN_DIR=${state}/bin"
            "NPM_CONFIG_PREFIX=${state}"
            "NPM_CONFIG_CACHE=${state}/npm-cache"
            # keep tool state/caches in the persistent /opt/agent volume, not
            # in the container's throwaway home
            "XDG_STATE_HOME=${state}/state"
            "XDG_CACHE_HOME=${state}/cache"
            # claude-code comes from the read-only store — never let it self-update
            "DISABLE_AUTOUPDATER=1"
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1"
            "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
            "TERM=xterm-256color"
            "LANG=C.UTF-8"
          ];
        };
      };

      readme = pkgs.writeTextDir "share/doc/${name}/README.md" ''
        # agentbox

        Single Nix flake producing an OCI image with opencode, Claude Code,
        OpenPackage (`opkg`), the jCodeMunch MCP server, and a Coursier-managed
        Scala toolchain on JDK 25. Agent configs and auth are bind-mounted from
        the host, so the container shares your existing setup.

        ## Use

            nix run .#                 # bash in the container, cwd as /work
            nix run .# -- opencode
            nix run .# -- claude

        Unfree packages (claude-code) are allowed in the flake itself — no env
        vars or `--impure` needed. The dashboard port 8080 is published;
        override with `AGENTBOX_PORT=8081`, disable with `AGENTBOX_PORT=`.

        The wrapper loads the image into Docker when the build changes. Inside
        the box, `devbox readme` shows this document. Requires an
        x86_64-linux builder; on macOS/aarch64 add a linux builder or change
        `system` and confirm `pkgs.opencode` / `pkgs.claude-code` build there.

        ## Shared with the host

        | Host                          | Container                 |
        |-------------------------------|---------------------------|
        | `~/.claude`                   | `~/.claude`               |
        | `~/.claude.json`              | `~/.claude.json`          |
        | `~/.config/opencode`          | `~/.config/opencode`      |
        | `~/.local/share/opencode`     | `~/.local/share/opencode` |
        | `~/.openpackage`              | `~/.openpackage`          |
        | `~/.agentbox`                 | `/opt/agent`              |
        | `$PWD`                        | `/work`                   |
        | `~/.gitconfig`                | `~/.gitconfig` (ro)       |
        | `~/.ssh`                      | `~/.ssh` (ro)             |
        | `~/.config/gh`                | `~/.config/gh`            |

        Claude Code, opencode and opkg all live in the Nix store or `/opt/agent`
        and are pinned; only their state is shared. Sessions, project history
        and settings written inside the container land in your real `~/.claude`.

        On Linux the Claude Code credential file is `~/.claude/.credentials.json`
        and is therefore inside the container. On macOS credentials are in the
        Keychain, so the container starts logged out — export `ANTHROPIC_API_KEY`
        or run `claude` once inside and log in. Either way, auth tokens are
        readable by anything running in there. Trusted repos only; a container
        is not an exfiltration boundary.

        `scala` and `scalafmt` (Coursier) install into `/opt/agent` on first
        run — network needed once, then cached; `sbt`, the agents and all
        other tools come pinned from nixpkgs. Tool caches and XDG state/cache
        also live in `/opt/agent`, so they survive container restarts.

        ## Permissions

        Processes run as your host uid/gid: the entrypoint starts as root,
        registers you in the container's `/etc/passwd`, takes ownership of
        `/opt/agent`, then drops back via `su-exec`. Files created in `/work`
        and in the mounts are owned by you on the host — no root-owned strays.

        Claude Code's own allow/deny rules go in `.claude/settings.json` in the
        repo (not `settings.local.json`, which "don't ask again" rewrites).
        Rules evaluate deny → ask → allow, with `defaultMode` as fallback:

            {
              "permissions": {
                "defaultMode": "acceptEdits",
                "allow": ["Bash(sbt test:*)", "Bash(git diff:*)", "Bash(rg:*)"],
                "deny":  ["Read(**/.env)"]
              }
            }

        That layer is enforced by Claude Code. The image contents are the real
        boundary — nothing on the host is reachable except the mounts above.

        ## Git & GitHub

        `gh` is in the image; your `~/.config/gh` (auth), `.gitconfig` and
        `~/.ssh` are mounted, so commits authored inside carry your identity
        and SSH commit signing works. GPG signing is not wired up — it would
        need the gpg-agent socket forwarded.

        ## OpenPackage

        `opkg` manages rules, commands, agents, skills and MCP configs across
        platforms, including both agents in this image:

            opkg install essentials
            opkg install gh@anthropics/claude-code --plugins code-review
            opkg install <resource> --platforms claudecode opencode
            opkg list -f

        `-g` installs to `~/` instead of the workspace. Overrides live in
        `<cwd>/.openpackage/platforms.jsonc` and `~/.openpackage/platforms.jsonc`,
        deep-merged local > global > built-in.

        ## jCodeMunch

        For opencode, `~/.config/opencode/opencode.json`:

            { "mcp": { "jcodemunch": { "type": "local",
                "command": ["jcodemunch-mcp"], "enabled": true } } }

        For Claude Code, `.mcp.json` at the repo root:

            { "mcpServers": { "jcodemunch": { "command": "jcodemunch-mcp" } } }

        Or write it once as an `mcp.jsonc` in an opkg package and let `opkg`
        emit both. Index a repo once from `/work` and the agent retrieves by
        symbol rather than reading whole files. Free for personal use, paid for
        commercial.

        ## Pinning

        nixpkgs is locked, so opencode, claude-code, sbt, JDK, Node and
        Coursier are reproducible — `nix flake update` is the only way they
        move. The two bootstrapped tool sets resolve latest; pin by hand if it
        matters:

            uv tool install jcodemunch-mcp==1.20.0
            npm install -g opkg@0.11.3

        ## Escape hatches

            nix build .#image       # just the tarball
            nix develop             # same toolchain on the host, no container
            AGENTBOX_RELOAD=1 nix run .#   # re-load the image into Docker

        If sbt/zinc throws `NoSuchFileException` on `.semanticdb` files, the
        shared `target/` holds partial state from an interrupted run —
        `rm -rf target` and recompile.
      '';

      agentbox = pkgs.writeShellApplication {
        name = name;
        text = ''
          IMG="${name}:${tag}"
          # the store path identifies the exact build; tagging it lets a plain
          # inspect decide freshness without touching the 1.5G tarball
          BUILD_TAG="${name}:build-$(basename "${image}")"

          command -v docker >/dev/null || { echo "docker not found on PATH" >&2; exit 1; }

          if [ "''${AGENTBOX_RELOAD:-0}" = "1" ] || ! docker image inspect "$BUILD_TAG" >/dev/null 2>&1; then
            echo "==> loading $IMG" >&2
            docker load --input ${image} >&2
            docker tag "$IMG" "$BUILD_TAG"
          fi

          CFG="''${XDG_CONFIG_HOME:-$HOME/.config}/opencode"
          GH="''${XDG_CONFIG_HOME:-$HOME/.config}/gh"
          DATA="''${XDG_DATA_HOME:-$HOME/.local/share}/opencode"
          OPKG="$HOME/.openpackage"
          STATE="$HOME/.agentbox"
          mkdir -p "$CFG" "$DATA" "$OPKG" "$STATE"/{home-cache,home-sbt} "$HOME/.claude"

          MOUNTS=(
            -v "$HOME/.claude:/home/dev/.claude"
            -v "$HOME/.claude.json:/home/dev/.claude.json"
            -v "$CFG:/home/dev/.config/opencode"
            -v "$DATA:/home/dev/.local/share/opencode"
            -v "$OPKG:/home/dev/.openpackage"
            -v "$STATE:/opt/agent"
            -v "$STATE/home-cache:/home/dev/.cache"
            -v "$STATE/home-sbt:/home/dev/.sbt"
            -v "$PWD:/work"
          )
          # identity, when present: gh auth persists; git config and ssh keys
          # read-only so the box can sign/author but not destroy
          if [ -d "$GH" ]; then MOUNTS+=(-v "$GH:/home/dev/.config/gh"); fi
          if [ -f "$HOME/.gitconfig" ]; then MOUNTS+=(-v "$HOME/.gitconfig:/home/dev/.gitconfig:ro"); fi
          if [ -d "$HOME/.ssh" ]; then MOUNTS+=(-v "$HOME/.ssh:/home/dev/.ssh:ro"); fi

          # must exist as a file, or docker creates a directory in its place
          [ -f "$HOME/.claude.json" ] || echo '{}' > "$HOME/.claude.json"

          # publish 8080 for the Datastar dashboard; AGENTBOX_PORT remaps it,
          # AGENTBOX_PORT= (empty) disables publishing
          PORT="''${AGENTBOX_PORT-8080}"
          if [ -n "$PORT" ]; then PORT_ARGS=(-p "''${PORT}:8080"); else PORT_ARGS=(); fi

          exec docker run --rm -it \
            -e HOST_UID="$(id -u)" \
            -e HOST_GID="$(id -g)" \
            -e HOME=/home/dev \
            "''${PORT_ARGS[@]}" \
            "''${MOUNTS[@]}" \
            -w /work \
            "$IMG" "$@"
        '';
      };
    in
    {
      packages.${system} = {
        default = pkgs.symlinkJoin {
          name = "agentbox-1.0";
          paths = [
            agentbox
            readme
          ];
          meta.mainProgram = name;
        };
        image = image;
      };

      apps.${system}.default = {
        type = "app";
        program = "${self.packages.${system}.default}/bin/${name}";
      };

      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          opencode
          claude-code
          coursier
          jdk
          uv
          nodejs_22
          git
        ];
      };
    };
}
