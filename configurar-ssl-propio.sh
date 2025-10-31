#!/bin/bash

# Script para configurar NGINX con certificado SSL propio
# Para el subdominio ueb-impresoras.ueb.edu.ec

set -e

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuración
SUBDOMINIO="ueb-impresoras.ueb.edu.ec"
CERT_DIR="/etc/ssl/certs/ueb"
KEY_DIR="/etc/ssl/private"

echo -e "${BLUE}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║    Configuración de SSL/HTTPS con Certificado Propio     ║${NC}"
echo -e "${BLUE}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""

# Verificar que se ejecuta como root
if [[ $EUID -ne 0 ]]; then
   echo -e "${RED}✗ Este script debe ejecutarse como root (sudo)${NC}"
   exit 1
fi

echo -e "${GREEN}✓ Permisos de root verificados${NC}"
echo ""

# PASO 1: Verificar que los archivos de certificado existen
echo -e "${BLUE}[1/6] Verificando archivos de certificado...${NC}"

CURRENT_DIR=$(pwd)
CERT_FILE="$CURRENT_DIR/ssl/STAR_ueb_edu_ec.crt"
KEY_FILE="$CURRENT_DIR/ssl/private.key"

if [ ! -f "$CERT_FILE" ]; then
    echo -e "${RED}✗ No se encontró el certificado: $CERT_FILE${NC}"
    exit 1
fi

if [ ! -f "$KEY_FILE" ]; then
    echo -e "${RED}✗ No se encontró la llave privada: $KEY_FILE${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Certificado encontrado: $CERT_FILE${NC}"
echo -e "${GREEN}✓ Llave privada encontrada: $KEY_FILE${NC}"

# Verificar formato del certificado
if openssl x509 -in "$CERT_FILE" -noout -text &>/dev/null; then
    echo -e "${GREEN}✓ Formato del certificado válido${NC}"
    
    # Mostrar información del certificado
    echo ""
    echo -e "${BLUE}Información del certificado:${NC}"
    openssl x509 -in "$CERT_FILE" -noout -subject -issuer -dates | sed 's/^/  /'
else
    echo -e "${RED}✗ Error: El certificado no es válido${NC}"
    exit 1
fi

# Verificar la llave privada
if openssl rsa -in "$KEY_FILE" -check -noout &>/dev/null; then
    echo -e "${GREEN}✓ Llave privada válida${NC}"
else
    echo -e "${RED}✗ Error: La llave privada no es válida${NC}"
    exit 1
fi

echo ""

# PASO 2: Crear directorios para los certificados
echo -e "${BLUE}[2/6] Creando directorios de certificados...${NC}"

mkdir -p "$CERT_DIR"
mkdir -p "$KEY_DIR"

echo -e "${GREEN}✓ Directorios creados${NC}"

# PASO 3: Copiar certificados
echo ""
echo -e "${BLUE}[3/6] Copiando certificados al sistema...${NC}"

# Copiar certificado
cp "$CERT_FILE" "$CERT_DIR/ueb-wildcard.crt"
chmod 644 "$CERT_DIR/ueb-wildcard.crt"
echo -e "${GREEN}✓ Certificado copiado a: $CERT_DIR/ueb-wildcard.crt${NC}"

# Copiar llave privada
cp "$KEY_FILE" "$KEY_DIR/ueb-wildcard.key"
chmod 600 "$KEY_DIR/ueb-wildcard.key"
echo -e "${GREEN}✓ Llave privada copiada a: $KEY_DIR/ueb-wildcard.key${NC}"

# PASO 4: Backup de configuración anterior
echo ""
echo -e "${BLUE}[4/6] Respaldando configuración anterior...${NC}"

if [ -f /etc/nginx/sites-available/ueb-impresoras ]; then
    cp /etc/nginx/sites-available/ueb-impresoras \
       /etc/nginx/sites-available/ueb-impresoras.backup.$(date +%Y%m%d_%H%M%S)
    echo -e "${GREEN}✓ Backup creado${NC}"
fi

# PASO 5: Crear nueva configuración de NGINX con SSL
echo ""
echo -e "${BLUE}[5/6] Configurando NGINX para HTTPS...${NC}"

cat > /etc/nginx/sites-available/ueb-impresoras << 'EOFNGINX'
# Redirigir HTTP a HTTPS
server {
    listen 80;
    server_name ueb-impresoras.ueb.edu.ec;
    
    # Redirigir todo el tráfico HTTP a HTTPS
    return 301 https://$server_name$request_uri;
}

# Servidor HTTPS
server {
    listen 443 ssl http2;
    server_name ueb-impresoras.ueb.edu.ec;
    
    # Certificados SSL
    ssl_certificate /etc/ssl/certs/ueb/ueb-wildcard.crt;
    ssl_certificate_key /etc/ssl/private/ueb-wildcard.key;
    
    # Configuración SSL moderna y segura
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384';
    ssl_prefer_server_ciphers off;
    
    # Seguridad adicional
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;
    ssl_session_tickets off;
    
    # OCSP Stapling
    ssl_stapling on;
    ssl_stapling_verify on;
    
    # Headers de seguridad
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    
    # Logs específicos
    access_log /var/log/nginx/ueb-impresoras-access.log;
    error_log /var/log/nginx/ueb-impresoras-error.log;
    
    # Tamaño máximo de archivo (para PDFs grandes)
    client_max_body_size 100M;
    
    # Proxy al puerto 8080 (aplicación Java)
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
        
        # Timeouts para operaciones de impresión
        proxy_connect_timeout 600s;
        proxy_send_timeout 600s;
        proxy_read_timeout 600s;
        
        # Buffering
        proxy_buffering off;
        proxy_request_buffering off;
    }
    
    # WebSocket support (si se necesita en el futuro)
    location /ws {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }
}
EOFNGINX

echo -e "${GREEN}✓ Configuración de NGINX actualizada${NC}"

# PASO 6: Verificar y aplicar configuración
echo ""
echo -e "${BLUE}[6/6] Verificando y aplicando cambios...${NC}"

# Verificar sintaxis de NGINX
if nginx -t 2>&1 | grep -q "successful"; then
    echo -e "${GREEN}✓ Configuración de NGINX válida${NC}"
else
    echo -e "${RED}✗ Error en configuración de NGINX${NC}"
    nginx -t
    exit 1
fi

# Verificar firewall
if command -v ufw &> /dev/null; then
    if ufw status | grep -q "Status: active"; then
        echo -e "${YELLOW}Configurando firewall...${NC}"
        ufw allow 443/tcp comment 'HTTPS - Print Manager' >/dev/null 2>&1
        echo -e "${GREEN}✓ Puerto 443 permitido en firewall${NC}"
    fi
fi

# Reiniciar NGINX
echo -e "${YELLOW}Reiniciando NGINX...${NC}"
systemctl restart nginx

if systemctl is-active --quiet nginx; then
    echo -e "${GREEN}✓ NGINX reiniciado correctamente${NC}"
else
    echo -e "${RED}✗ Error al reiniciar NGINX${NC}"
    systemctl status nginx
    exit 1
fi

# RESULTADO FINAL
echo ""
echo -e "${GREEN}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║            ✓ SSL CONFIGURADO EXITOSAMENTE                ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${BLUE}📋 INFORMACIÓN DEL CERTIFICADO:${NC}"
echo ""
openssl x509 -in "$CERT_DIR/ueb-wildcard.crt" -noout -subject -issuer -dates | sed 's/^/   /'
echo ""
echo -e "${BLUE}🌐 ACCESO AL SISTEMA:${NC}"
echo ""
echo -e "   ${GREEN}HTTPS (Seguro):${NC}  https://$SUBDOMINIO"
echo -e "   ${YELLOW}HTTP:${NC}            http://$SUBDOMINIO ${YELLOW}(redirige automáticamente a HTTPS)${NC}"
echo ""
echo -e "${BLUE}📱 PARA SCRIPTS .BAT:${NC}"
echo ""
echo -e "   Los scripts .bat siguen usando HTTP con IP directa:"
echo -e "   ${GREEN}http://10.1.16.31:8080${NC}"
echo ""
echo -e "${BLUE}🔒 ARCHIVOS DEL CERTIFICADO:${NC}"
echo ""
echo -e "   Certificado:    ${GREEN}$CERT_DIR/ueb-wildcard.crt${NC}"
echo -e "   Llave privada:  ${GREEN}$KEY_DIR/ueb-wildcard.key${NC}"
echo ""
echo -e "${BLUE}📊 VERIFICAR CONFIGURACIÓN:${NC}"
echo ""
echo -e "   Probar HTTPS localmente:"
echo -e "   ${BLUE}curl -I https://$SUBDOMINIO${NC}"
echo ""
echo -e "   Ver logs de NGINX:"
echo -e "   ${BLUE}sudo tail -f /var/log/nginx/ueb-impresoras-access.log${NC}"
echo ""
echo -e "   Verificar certificado:"
echo -e "   ${BLUE}openssl s_client -connect $SUBDOMINIO:443 -showcerts${NC}"
echo ""
echo -e "${BLUE}🔧 COMANDOS ÚTILES:${NC}"
echo ""
echo -e "   Ver configuración actual:"
echo -e "   ${BLUE}cat /etc/nginx/sites-available/ueb-impresoras${NC}"
echo ""
echo -e "   Reiniciar NGINX:"
echo -e "   ${BLUE}sudo systemctl restart nginx${NC}"
echo ""
echo -e "   Ver estado de NGINX:"
echo -e "   ${BLUE}sudo systemctl status nginx${NC}"
echo ""
echo -e "${YELLOW}⚠️  IMPORTANTE - RENOVACIÓN DEL CERTIFICADO:${NC}"
echo ""
echo -e "   Este es un certificado wildcard (*.ueb.edu.ec)"
echo -e "   Cuando venza, deberás:"
echo -e "   1. Obtener el nuevo certificado de tu proveedor"
echo -e "   2. Reemplazar los archivos en $CERT_DIR y $KEY_DIR"
echo -e "   3. Reiniciar NGINX: ${BLUE}sudo systemctl restart nginx${NC}"
echo ""
echo -e "${GREEN}✓ ¡Todo listo! Ahora puedes acceder de forma segura con HTTPS.${NC}"
echo ""
