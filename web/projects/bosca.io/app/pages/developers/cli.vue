<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Bosca CLI',
  description: 'Install the client, connect to a local instance, and manage content and other platform features.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Bosca CLI</h1>
    <p class="subtitle">
      Install the client, connect to a local instance, and manage content and other platform features.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      The Bosca CLI (<code>bosca</code>) is built with <strong>Clikt</strong> and <strong>Apollo Kotlin</strong>. It communicates with the server via GraphQL and provides commands for platform administration. It compiles to a <strong>GraalVM native binary</strong> for fast startup.
    </p>

    <h2 id="install">
      Install the CLI
    </h2>
    <CodeBlock
      lang="bash"
      :code="`curl -fsSL https://bosca.io/cli/install.sh | sh
bosca --version
bosca --help`"
    />
    <p>
      The installer downloads a platform package and verifies it against <code>SHA256SUMS</code>.
      Public releases are available from
      <a href="https://github.com/bosca-io/bosca/releases">Bosca releases</a>
      with <code>cli-v&lt;version&gt;</code> tags.
    </p>
    <h2 id="auth">
      Authentication
    </h2>
    <p>The CLI supports three authentication flows:</p>
    <CodeBlock
      lang="bash"
      :code="`# Password login
bosca login --profile local --url http://bosca.localhost:3000/graphql --username admin --password password

# OAuth2 browser flow (opens browser, callback on localhost:6979)
bosca login --url https://api.example.com/graphql --oauth2 google

# API token
bosca login --url https://api.example.com/graphql --api-token 'YOUR_API_TOKEN'`"
    />
    <p>
      Credentials are stored in <code>~/.config/bosca/config.json</code> (owner-read/write only). Password sessions refresh when needed. API tokens are stored directly.
    </p>
    <p>
      Start <NuxtLink to="/developers/run-locally">local Bosca</NuxtLink> before logging in.
      Replace <code>password</code> if you changed the initial administrator password.
      The endpoint is a GraphQL URL and includes <code>/graphql</code>.
    </p>
    <h2 id="profiles">
      Saved account profiles
    </h2>
    <CodeBlock
      lang="bash"
      :code="`bosca profile list
bosca profile show local
bosca profile use local
bosca --profile local data metadata list --limit 10
bosca logout --profile local`"
    />
    <p>
      A CLI profile identifies a server/account pair and is separate from a social profile.
      <code>profile use</code> changes the saved default; put <code>--profile</code> before the
      subcommand or set <code>BOSCA_PROFILE</code> for one invocation.
      Stored credentials are only sent to their saved server.
    </p>
    <p>
      Command-specific credential overrides vary. Use <code>--help</code> on your installed version
      to check supported <code>--url</code>, <code>--token</code>, and password options.
    </p>

    <p>
      Replace UUID placeholders with real IDs. Create commands return the new IDs; later commands
      need these values. The examples below assume a saved login with the required permissions.
    </p>
    <h2 id="commands">
      Command Tree
    </h2>

    <h3 id="data">
      Content Management (<code>data</code>)
    </h3>
    <CodeBlock
      lang="bash"
      :code="`# Bootstrap an instance from a manifest
bosca data install --manifest manifest.json --url https://api.example.com/graphql

# Metadata CRUD
bosca data metadata list
bosca data metadata get --id UUID
bosca data metadata create --name 'Example file' --content-type application/pdf
bosca data metadata set-content --id UUID --file ./document.pdf
bosca data metadata set-ready --id UUID

# Collection management
bosca data collection create --name 'Articles'
bosca data collection add-item --id COLLECTION_UUID --metadata-id METADATA_UUID
bosca data collection list-items --id COLLECTION_UUID

# Supplementary content
bosca data supplementary add --metadata-id UUID --key thumbnail --name Thumbnail --content-type image/png --file ./thumb.png
bosca data supplementary set-content --supplementary-id UUID --file ./updated.png

# Relationships
bosca data relationship add --id1 UUID --id2 UUID --relationship 'related'

# Templates
bosca data template list
bosca data template create-document --name 'Blog Post'`"
    />

    <h3 id="workops">
      Work Operations (<code>workops</code>)
    </h3>
    <p>Full issue tracking with portfolios, programs, projects, tasks, sprints, and boards:</p>
    <CodeBlock
      lang="bash"
      :code="`# Task management
bosca workops task create --project-key PROJ --summary 'Fix login bug'
bosca workops task list --project-key PROJ
bosca workops task transition --key PROJ-42 --transition-id TRANSITION_UUID
bosca workops task search --query 'project = PROJ AND status = Open'

# Sprint and board management
bosca workops sprint list --board-id UUID
bosca workops board list`"
    />

    <h3 id="localization">
      Localization (<code>localization</code>)
    </h3>
    <CodeBlock
      lang="bash"
      :code="`bosca localization upload --help
bosca localization download --help
bosca localization status --help
bosca localization sync --help`"
    />

    <h3 id="ci">
      CI/CD (<code>ci</code>)
    </h3>
    <CodeBlock
      lang="bash"
      :code="`# Agent management
bosca ci agent register --name build-1 --labels linux,docker --mode runner
bosca ci agent start --poll-interval 5
bosca ci agent list --status online

# Pipeline runs
bosca ci run trigger --pipeline-id UUID --ref main
bosca ci run logs --step-id UUID --run-id UUID --follow
bosca ci run cancel --id UUID

# Secrets
bosca ci secret set --repo-id UUID --name DEPLOY_TOKEN --value 'YOUR_SECRET'`"
    />

    <h3 id="tokens">
      API Tokens (<code>tokens</code>)
    </h3>
    <CodeBlock
      lang="bash"
      :code="`bosca tokens create --name 'CI Token' --scope content:edit --scope security:manage
bosca tokens list
bosca tokens revoke --id UUID
bosca tokens scopes    # List all available scopes`"
    />

    <h2 id="mcp">
      MCP Server
    </h2>
    <p>
      The CLI includes a <strong>Model Context Protocol (MCP) server</strong> for AI tool integration. It exposes structured tools for content management, task tracking, and more:
    </p>
    <CodeBlock
      lang="bash"
      :code="`# Start a STDIO MCP server for an MCP client
bosca mcp-server

# Install MCP config into AI tool clients
bosca mcp-install --help`"
    />
    <p>Use a saved account profile for the MCP server, for example <code>bosca --profile local mcp-server</code>. MCP tools include: <code>content_metadata</code> (CRUD + set content), <code>content_collection</code> (CRUD + items), <code>content_supplementary</code>, <code>content_relationship</code>, <code>content_template</code>, and full WorkOps task management.</p>
  </div>
</template>
