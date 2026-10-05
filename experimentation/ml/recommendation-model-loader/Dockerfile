FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

WORKDIR /app

COPY pyproject.toml uv.lock ./
# --no-install-project installs only the locked deps. The project itself is a flat module
# (model_loader.py) run via the venv interpreter directly, so packaging it (hatchling) here would
# fail before the source is even copied — there is no package dir named after the project.
RUN uv sync --frozen --no-dev --no-install-project

COPY model_loader.py .
COPY bosca_storage.py .
COPY bosca_selection.py .

# Invoke the venv interpreter directly: `uv run` would try to (re)build the flat-module project via
# hatchling at runtime and fail (there is no package dir named after the project).
ENTRYPOINT ["/app/.venv/bin/python", "model_loader.py"]
