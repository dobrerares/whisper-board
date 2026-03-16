{
  description = "Whisper Board - Android STT keyboard dev environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
          config.android_sdk.accept_license = true;
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          cmdLineToolsVersion = "13.0";
          platformToolsVersion = "35.0.2";
          buildToolsVersions = [ "35.0.0" ];
          platformVersions = [ "35" ];
          includeNDK = false;
          includeEmulator = false;
        };

        androidSdk = androidComposition.androidsdk;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            androidSdk
            pkgs.gradle
            pkgs.jdk17
            pkgs.kotlin
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}";
          GRADLE_OPTS = "-Dorg.gradle.daemon=true -Xmx2048m";

          shellHook = ''
            echo "🎙️ Whisper Board dev environment loaded"
            echo "  Android SDK: $ANDROID_HOME"
            echo "  Java:        $(java --version 2>&1 | head -1)"
            echo "  Gradle:      $(gradle --version 2>&1 | grep '^Gradle' || echo 'available')"
            echo "  Kotlin:      $(kotlin -version 2>&1)"
          '';
        };
      });
}
