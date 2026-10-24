FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TWOFOLD_DATA=/data
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && groupadd --gid 10001 twofold \
    && useradd --uid 10001 --gid 10001 --no-create-home twofold \
    && mkdir /data && chown 10001:10001 /data
COPY twofold ./twofold
RUN chmod 0755 /app \
    && find /app/twofold -type d -exec chmod 0755 {} + \
    && find /app/twofold -type f -exec chmod 0644 {} +
USER 10001:10001
RUN python -c "import twofold.app, twofold.admin"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=3).read()"]
CMD ["python", "-m", "twofold.app"]
