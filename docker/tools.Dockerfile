FROM python:3.13-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends openssl && rm -rf /var/lib/apt/lists/*
WORKDIR /tools
COPY docker/manage.py /tools/manage.py
USER 10001:10001
ENTRYPOINT ["python", "-u", "/tools/manage.py"]
