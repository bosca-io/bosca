FROM tensorflow/tensorflow:latest

COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

WORKDIR /app

COPY pyproject.toml uv.lock ./
# Pin the interpreter to 3.12: the locked TensorFlow 2.16.2 ships no wheels for uv's newer default
# (3.14). --no-install-project installs only the locked deps — the project itself is a flat module
# (train.py) run via `uv run python train.py`, so packaging it (hatchling) here would fail before the
# source is even copied.
RUN uv sync --frozen --no-dev --no-install-project --python 3.12

COPY train.py .
COPY bosca_storage.py .
COPY trainer ./trainer

EXPOSE 8090

# tensorflow-recommenders requires Keras 2, but TF 2.16+ defaults to Keras 3.
ENV TF_USE_LEGACY_KERAS=1
# Invoke the venv interpreter directly: `uv run` would try to (re)build the flat-module project via
# hatchling at runtime and fail (there is no package dir named after the project).
ENTRYPOINT ["/app/.venv/bin/python", "train.py"]
CMD ["serve", "--port", "8090"]
