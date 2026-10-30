# Artifact package installer

Shared composition library for the existing default Docker sync and raw publication pipeline package.
The API server and Atlas use the same installer and version, preserving operator graphs on reinstall.
Run `./gradlew :artifacts:artifacts-installer:test` from the workspace root.
