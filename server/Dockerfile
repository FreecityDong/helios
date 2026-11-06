FROM node:22-alpine
WORKDIR /app
COPY scene-proxy.mjs ./
ENV HOST=0.0.0.0 PORT=8787
USER node
EXPOSE 8787
CMD ["node", "scene-proxy.mjs"]
