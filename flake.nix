{
  description = "Android Kotlin development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.jdk21
            pkgs.gradle
          ];

          ANDROID_HOME = "/home/buddia/Android/Sdk";
          ANDROID_SDK_ROOT = "/home/buddia/Android/Sdk";
          JAVA_HOME = "${pkgs.jdk21}";

          shellHook = ''
            mkdir -p "$ANDROID_HOME"
            echo "sdk.dir=$ANDROID_HOME" > local.properties
            echo "Android SDK: $ANDROID_HOME"
            echo "Java: $(java -version 2>&1 | head -1)"
          '';
        };
      }
    );
}
