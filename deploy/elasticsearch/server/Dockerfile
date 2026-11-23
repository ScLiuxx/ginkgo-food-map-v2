FROM docker.elastic.co/elasticsearch/elasticsearch:9.5.4@sha256:82ac14f43fe701992e601f4cc81e1c0d7dbc5a2576d8cd736006452925df4026

# 安装包由prepare.py从官方源下载并核对SHA-256及插件描述，不在构建时联网下载。
COPY --chown=elasticsearch:root analysis-ik.zip /tmp/analysis-ik.zip
RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip \
    && rm /tmp/analysis-ik.zip
