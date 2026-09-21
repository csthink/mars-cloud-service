ARG BUSYBOX_IMAGE
ARG JAEGER_IMAGE
FROM ${BUSYBOX_IMAGE} AS probe
FROM ${JAEGER_IMAGE}
COPY --from=probe /bin/busybox /usr/local/bin/busybox
RUN ["/usr/local/bin/busybox", "true"]
