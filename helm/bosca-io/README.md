# Bosca website

The website's runtime environment is configured through `extraEnv`. To serve
the CLI installer from Bosca Artifacts, set this in the deployment's values:

```yaml
extraEnv:
  NUXT_CLI_INSTALL_SCRIPT_URL: https://<artifacts-host>/raw/bosca/bosca-cli/<cli-version>/install.sh
```

When deploying through `bosca-services`, put the same setting under the subchart:

```yaml
bosca-io:
  enabled: true
  extraEnv:
    NUXT_CLI_INSTALL_SCRIPT_URL: https://<artifacts-host>/raw/bosca/bosca-cli/<cli-version>/install.sh
```

Use a published CLI artifact version containing the updated `cli/install.sh`;
it is independent of the website image tag. The website derives the package
repository from this URL, so clients can use `curl ... | sh` without specifying
the artifact repository themselves. Apply the values with a Helm upgrade to
update the website environment.
