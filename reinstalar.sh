# Script de reinstalación completa
cat > ~/reinstall-clean.sh << 'ENDOFSCRIPT'
#!/bin/bash

set -e

echo "═══════════════════════════════════════════════════"
echo "  REINSTALACIÓN COMPLETA - PRINT MANAGER"
echo "═══════════════════════════════════════════════════"
echo ""

# 1. Detener y limpiar servicio actual
echo "▶ Deteniendo servicio actual..."
sudo systemctl stop print-manager || true
sudo systemctl disable print-manager || true

# 2. Hacer backup de la base de datos
echo "▶ Haciendo backup de BD..."
sudo -u postgres pg_dump impre > ~/impre_backup_$(date +%Y%m%d_%H%M%S).sql

# 3. Limpiar instalación anterior
echo "▶ Limpiando instalación anterior..."
sudo rm -rf /opt/print-manager.old || true
sudo mv /opt/print-manager /opt/print-manager.old || true

# 4. Crear nuevo directorio
echo "▶ Creando nuevo directorio..."
sudo mkdir -p /opt/print-manager

# 5. Compilar desde el directorio del usuario
echo "▶ Compilando desde ~/Servidori..."
cd ~/Servidori
git pull origin main
mvn clean package -DskipTests -q

# 6. Copiar código ya compilado
echo "▶ Copiando código compilado a /opt/print-manager..."
sudo cp -r ~/Servidori/* /opt/print-manager/

# 7. Configurar application.properties
echo "▶ Configurando..."
cat > /tmp/application.properties << 'EOFAPP'
server.port=8080
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.url=jdbc:postgresql://localhost:5432/impre
spring.datasource.username=postgres
spring.datasource.password=1212
spring.jpa.hibernate.ddl-auto=update
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.show-sql=false
spring.jpa.defer-datasource-initialization=true
spring.sql.init.mode=always
logging.level.root=info
logging.level.es.ucm.fdi.iu=info
es.ucm.fdi.base-path=/opt/print-manager/data/uploads
samba.guest.access=yes
samba.browseable=yes
spring.thymeleaf.cache=true
EOFAPP

sudo cp /tmp/application.properties /opt/print-manager/src/main/resources/application.properties

# 8. Crear directorio de datos
sudo mkdir -p /opt/print-manager/data/uploads

# 9. Ajustar permisos
echo "▶ Ajustando permisos..."
sudo chown -R printmgr:printmgr /opt/print-manager

# 10. Recrear servicio systemd
echo "▶ Configurando servicio..."
sudo tee /etc/systemd/system/print-manager.service > /dev/null << 'EOFSVC'
[Unit]
Description=Print Manager - Sistema de Gestión de Impresoras
After=network.target postgresql.service cups.service

[Service]
Type=simple
User=printmgr
WorkingDirectory=/opt/print-manager
ExecStart=/usr/bin/java -jar /opt/print-manager/target/iu-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOFSVC

# 11. Recargar y habilitar
sudo systemctl daemon-reload
sudo systemctl enable print-manager

# 12. Iniciar servicio
echo "▶ Iniciando servicio..."
sudo systemctl start print-manager

# 13. Esperar
echo "▶ Esperando 20 segundos..."
sleep 20

# 14. Verificar
echo ""
echo "═══════════════════════════════════════════════════"
if sudo systemctl is-active --quiet print-manager; then
    echo "✓ Servicio ACTIVO"
    
    IP=$(curl -s http://localhost:8080/print-server/api/printers 2>/dev/null | grep -o '"serverIp":"[^"]*"' | cut -d'"' -f4)
    REAL_IP=$(hostname -I | awk '{print $1}')
    
    echo "✓ IP del sistema: $REAL_IP"
    echo "✓ IP detectada por app: $IP"
    echo ""
    echo "URL: http://$REAL_IP:8080"
    echo "Usuario: admin"
    echo "Password: admin"
else
    echo "✗ Servicio NO ACTIVO"
    echo ""
    echo "Ver logs:"
    echo "  sudo journalctl -u print-manager -n 50"
fi
echo "═══════════════════════════════════════════════════"
ENDOFSCRIPT

chmod +x ~/reinstall-clean.sh