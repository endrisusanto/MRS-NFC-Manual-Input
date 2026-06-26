FROM node:20-alpine
WORKDIR /app
COPY server/package*.json ./
RUN npm install --omit=dev
COPY server/index.js ./
COPY MRS-NFC-Manual-Input/web/ ./web/
EXPOSE 3000
CMD ["node", "index.js"]
