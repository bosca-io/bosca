<script setup lang="ts">
useSeoMeta({ title: 'Bosca CLI' })
</script>

<template>
  <div class="doc-content article">
    <h1>Bosca CLI</h1>
    <p class="subtitle">
      A command-line tool for managing content, work tracking, localization, CI/CD, API tokens, and AI tool integration — with GraalVM native binary support.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      The Bosca CLI (<code>bosca</code>) is built with <strong>Clikt</strong> and <strong>Apollo Kotlin</strong>. It communicates with the server via GraphQL and provides commands for every major subsystem. It compiles to a <strong>GraalVM native binary</strong> for fast startup.
    </p>

    <h2 id="auth">
      Authentication
    </h2>
    <p>The CLI supports three authentication flows:</p>
    <CodeBlock
      lang="bash"
      :code="`# Password login
bosca login --url https://api.example.com --username admin --password secret

# OAuth2 browser flow (opens browser, callback on localhost:6979)
bosca login --url https://api.example.com --oauth2 google

# API token
bosca login --url https://api.example.com --api-token sk-abc123`"
    />
    <p>
      Credentials are stored in <code>~/.config/bosca/config.json</code> (owner-read/write only). Tokens are automatically refreshed on each command invocation.
    </p>
    <p>Every command also accepts <code>--url</code>, <code>--token</code>, or <code>--username</code>/<code>--password</code> overrides, plus environment variables <code>BOSCA_ENDPOINT</code>, <code>BOSCA_TOKEN</code>, <code>BOSCA_USERNAME</code>, <code>BOSCA_PASSWORD</code>.</p>

    <h2 id="commands">
      Command Tree
    </h2>

    <h3 id="data">
      Content Management (<code>data</code>)
    </h3>
    <CodeBlock
      lang="bash"
      :code="`# Bootstrap an instance from a manifest
bosca data install --manifest manifest.json --url https://api.example.com

# Metadata CRUD
bosca data metadata list
bosca data metadata get --id <uuid>
bosca data metadata create --name 'My Document' --type document
bosca data metadata set-content --id <uuid> --file ./document.pdf
bosca data metadata set-ready --id <uuid>

# Collection management
bosca data collection create --name 'Articles'
bosca data collection add-item --id <collection-uuid> --metadata-id <metadata-uuid>
bosca data collection list-items --id <collection-uuid>

# Supplementary content
bosca data supplementary add --metadata-id <uuid> --key thumbnail --file ./thumb.png
bosca data supplementary set-content --id <uuid> --file ./updated.png

# Relationships
bosca data relationship add --id <uuid> --metadata-id <uuid> --relationship 'related'

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
bosca workops task transition --key PROJ-42 --transition-id <transition-uuid>
bosca workops task search --query 'project = PROJ AND status = Open'

# Sprint and board management
bosca workops sprint list --project-key PROJ
bosca workops board list`"
    />

    <h3 id="localization">
      Localization (<code>localization</code>)
    </h3>
    <CodeBlock
      lang="bash"
      :code="`bosca localization upload      # Push source strings to translation service
bosca localization download    # Pull translated strings
bosca localization status      # Coverage report
bosca localization sync        # Round-trip sync`"
    />

    <h3 id="ci">
      CI/CD (<code>ci</code>)
    </h3>
    <CodeBlock
      lang="bash"
      :code="`# Agent management
bosca ci agent register --name build-1 --labels linux,docker --mode runner
bosca ci agent start --poll-interval 5s
bosca ci agent list --status online

# Pipeline runs
bosca ci run trigger --pipeline-id <uuid> --ref main
bosca ci run logs --step-id <uuid> --follow
bosca ci run cancel --id <uuid>

# Secrets
bosca ci secret set --repo-id <uuid> --key DEPLOY_TOKEN --value abc123`"
    />

    <h3 id="tokens">
      API Tokens (<code>tokens</code>)
    </h3>
    <CodeBlock
      lang="bash"
      :code="`bosca tokens create --name 'CI Token' --scopes content:edit,security:manage
bosca tokens list
bosca tokens revoke --id <uuid>
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
      :code="`# Start STDIO MCP server (for AI assistants like Claude Code)
bosca mcp-server

# Install MCP config into AI tool clients
bosca mcp-install`"
    />
    <p>MCP tools include: <code>content_metadata</code> (CRUD + set content), <code>content_collection</code> (CRUD + items), <code>content_supplementary</code>, <code>content_relationship</code>, <code>content_template</code>, and full WorkOps task management.</p>
  </div>
</template>
