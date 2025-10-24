# Guía de Configuración para Clientes

## 📱 Conectar Clientes al Servidor de Impresión

Esta guía explica cómo configurar diferentes sistemas operativos para usar las impresoras compartidas.

---

## 🪟 Windows 10/11

### Método 1: Agregar Impresora por Red (Recomendado)

1. **Abrir Configuración de Impresoras**
   - Presiona `Windows + I`
   - Ve a **Dispositivos > Impresoras y escáneres**
   - Click en **Agregar una impresora o escáner**

2. **Buscar Impresora en Red**
   - Espera a que Windows detecte impresoras
   - Si aparece tu impresora, selecciónala y click **Agregar dispositivo**

3. **Agregar Manualmente (si no se detecta)**
   - Click en **La impresora que deseo no está en la lista**
   - Selecciona **Seleccionar una impresora compartida por nombre**
   - Ingresa: `\\IP-DEL-SERVIDOR\NOMBRE-IMPRESORA`
   - Ejemplo: `\\192.168.1.100\HP_LaserJet`
   - Click **Siguiente**

4. **Autenticación**
   - Si se solicita, ingresa:
     - **Usuario**: tu_usuario_samba
     - **Contraseña**: tu_password_samba
   - Marca **Recordar credenciales**

5. **Instalar Drivers**
   - Windows buscará drivers automáticamente
   - Si no encuentra, selecciona el fabricante y modelo manualmente

### Método 2: Usar IPP (Internet Printing Protocol)

1. **Panel de Control**
   - Abre **Panel de Control > Dispositivos e impresoras**
   - Click en **Agregar una impresora**

2. **Agregar impresora de red**
   - Selecciona **Agregar una impresora de red, inalámbrica o Bluetooth**
   - Click **La impresora que deseo no está en la lista**

3. **Especificar URL**
   - Selecciona **Seleccionar una impresora compartida por nombre**
   - Ingresa: `http://IP-SERVIDOR:631/printers/NOMBRE-IMPRESORA`
   - Ejemplo: `http://192.168.1.100:631/printers/HP_LaserJet`

4. **Completar instalación**
   - Selecciona driver apropiado
   - Da nombre a la impresora
   - Click **Finalizar**

### Método 3: PowerShell (Avanzado)

```powershell
# Agregar impresora Samba
Add-Printer -ConnectionName "\\192.168.1.100\HP_LaserJet"

# Agregar impresora IPP
Add-Printer -Name "HP_LaserJet" -DriverName "HP LaserJet Pro" -PortName "http://192.168.1.100:631/printers/HP_LaserJet"

# Ver impresoras instaladas
Get-Printer
```

### Solución de Problemas Windows

**Problema: "Windows no puede conectar con la impresora"**
```cmd
# Habilitar File and Printer Sharing
netsh advfirewall firewall set rule group="File and Printer Sharing" new enable=Yes

# Habilitar SMB
Enable-WindowsOptionalFeature -Online -FeatureName SMB1Protocol
```

**Problema: "Acceso denegado"**
- Verifica credenciales de Samba
- Asegúrate de que el usuario exista en el servidor
- Verifica que el usuario tenga permisos en el grupo `printusers`

---

## 🍎 macOS

### Agregar Impresora Samba

1. **Abrir Preferencias del Sistema**
   - Menu Apple  > **Preferencias del Sistema**
   - Click en **Impresoras y Escáneres**

2. **Agregar Impresora**
   - Click en el botón **+** (más)
   - Selecciona pestaña **Windows**

3. **Buscar Servidor**
   - En la barra de direcciones, ingresa: `smb://IP-SERVIDOR`
   - Ejemplo: `smb://192.168.1.100`
   - Aparecerán las impresoras compartidas

4. **Autenticación**
   - Nombre: tu_usuario_samba
   - Contraseña: tu_password_samba
   - Selecciona la impresora

5. **Instalar Driver**
   - macOS buscará driver automáticamente
   - O selecciona **Software de impresora** apropiado

### Agregar Impresora IPP

1. **Abrir Preferencias**
   - Menu Apple  > **Preferencias del Sistema > Impresoras y Escáneres**
   - Click **+**

2. **Usar Protocolo IPP**
   - Selecciona pestaña **IP**
   - Protocolo: **IPP**
   - Dirección: `IP-SERVIDOR`
   - Cola: `printers/NOMBRE-IMPRESORA`
   - Ejemplo Cola: `printers/HP_LaserJet`

3. **Configurar**
   - Nombre: Nombre descriptivo
   - Ubicación: (opcional)
   - Usar: Selecciona driver apropiado

### Terminal macOS

```bash
# Agregar impresora IPP
lpadmin -p HP_LaserJet \
  -E \
  -v ipp://192.168.1.100:631/printers/HP_LaserJet \
  -m everywhere

# Listar impresoras
lpstat -p -d

# Imprimir archivo de prueba
lp -d HP_LaserJet archivo.pdf
```

---

## 🐧 Linux (Ubuntu/Debian)

### Método 1: Interfaz Gráfica (GNOME)

1. **Abrir Configuración**
   - **Configuración > Impresoras**
   - O busca "Impresoras" en el menú de aplicaciones

2. **Agregar Impresora**
   - Click en **Agregar**
   - Espera a que se detecten impresoras

3. **Seleccionar Tipo**
   - **Impresora de red**: Para Samba
   - **Impresora IPP**: Para conexión directa CUPS

4. **Configurar Samba**
   - URI: `smb://IP-SERVIDOR/NOMBRE-IMPRESORA`
   - Usuario y contraseña si es necesario
   - Click **Adelante**

5. **Seleccionar Driver**
   - Busca el fabricante y modelo
   - O selecciona **Generic PostScript Printer**

### Método 2: system-config-printer

```bash
# Instalar herramienta
sudo apt install system-config-printer

# Ejecutar
sudo system-config-printer
```

En la interfaz:
1. **Add > Network Printer**
2. **Windows Printer via SAMBA**
3. Ingresa: `IP-SERVIDOR/NOMBRE-IMPRESORA`
4. Dominio: WORKGROUP (o tu dominio)
5. Usuario y contraseña Samba
6. Selecciona driver

### Método 3: Línea de Comandos

#### Agregar Impresora Samba

```bash
# Instalar smbclient
sudo apt install smbclient

# Agregar impresora
sudo lpadmin -p HP_LaserJet \
  -E \
  -v smb://usuario:password@192.168.1.100/HP_LaserJet \
  -m drv:///sample.drv/generic.ppd

# Configurar como predeterminada
lpoptions -d HP_LaserJet
```

#### Agregar Impresora IPP

```bash
# Detectar impresoras IPP
lpinfo -v | grep ipp

# Agregar impresora
sudo lpadmin -p HP_LaserJet \
  -E \
  -v ipp://192.168.1.100:631/printers/HP_LaserJet \
  -m everywhere

# Habilitar impresora
sudo cupsenable HP_LaserJet
sudo cupsaccept HP_LaserJet
```

#### Ver y Probar

```bash
# Listar impresoras
lpstat -p -d

# Ver información detallada
lpstat -l -p HP_LaserJet

# Imprimir prueba
echo "Test de impresión" | lp -d HP_LaserJet

# Ver trabajos en cola
lpstat -o

# Cancelar trabajo
cancel HP_LaserJet-123
```

### Solución de Problemas Linux

**Error: "Unable to connect to CIFS host"**

```bash
# Instalar paquetes necesarios
sudo apt install smbclient cifs-utils

# Probar conexión
smbclient -L //192.168.1.100 -U usuario

# Verificar que puedes ver la impresora
smbclient //192.168.1.100/HP_LaserJet -U usuario
```

**Error de autenticación**

```bash
# Editar /etc/cups/printers.conf (como root)
sudo nano /etc/cups/printers.conf

# Agregar línea:
AuthInfoRequired username,domain,password
```

---

## 📱 Android

### Usar Google Cloud Print (Depreciado)

Google Cloud Print fue descontinuado en 2020. Alternativas:

### Usar App de Terceros

1. **PrintHand** (Recomendado)
   - Descarga desde Play Store
   - Soporta SMB, IPP, y más
   - Configuración fácil

2. **Configuración**
   - Tipo: Samba o IPP
   - Servidor: IP-SERVIDOR
   - Impresora: NOMBRE-IMPRESORA
   - Credenciales si es necesario

### Usar Mopria (Si la impresora lo soporta)

1. Descarga **Mopria Print Service** de Play Store
2. La aplicación detectará impresoras automáticamente
3. Imprime desde cualquier app con opción de imprimir

---

## 🍏 iOS/iPadOS

### AirPrint

Si el servidor CUPS está configurado con Avahi/Bonjour:

1. **Imprimir desde cualquier app**
   - Toca el ícono **Compartir**
   - Selecciona **Imprimir**
   - Toca **Seleccionar impresora**
   - Elige tu impresora

### Apps de Terceros

**PrintCentral Pro**
- Soporta SMB e IPP
- Configuración manual de servidores

---

## 🔧 Configuración Avanzada

### Configurar Opciones de Impresión por Defecto

#### Windows
```powershell
# Establecer impresora predeterminada
Set-DefaultPrinter -Name "HP_LaserJet"

# Configurar opciones
Set-PrintConfiguration -PrinterName "HP_LaserJet" -Color $true -DuplexingMode TwoSidedLongEdge
```

#### Linux
```bash
# Opciones de impresión por defecto
lpoptions -p HP_LaserJet -o sides=two-sided-long-edge
lpoptions -p HP_LaserJet -o media=A4
lpoptions -p HP_LaserJet -o ColorModel=RGB

# Ver opciones actuales
lpoptions -p HP_LaserJet -l
```

#### macOS
```bash
# Establecer opciones
lpoptions -p HP_LaserJet -o sides=two-sided-long-edge
lpoptions -p HP_LaserJet -o media=iso_a4_210x297mm
```

### Imprimir con Opciones Específicas

```bash
# Imprimir doble cara
lp -d HP_LaserJet -o sides=two-sided-long-edge archivo.pdf

# Imprimir múltiples copias
lp -d HP_LaserJet -n 3 archivo.pdf

# Imprimir páginas específicas
lp -d HP_LaserJet -P 1-5,8,11-13 archivo.pdf

# Imprimir en blanco y negro
lp -d HP_LaserJet -o ColorModel=Gray archivo.pdf

# Imprimir con orientación
lp -d HP_LaserJet -o landscape archivo.pdf
```

---

## 📊 Monitoreo desde Cliente

### Ver estado de impresora

```bash
# Linux/macOS
lpstat -p HP_LaserJet

# Ver trabajos
lpstat -o HP_LaserJet

# Ver todas las impresoras
lpstat -p -d
```

### Cancelar trabajo de impresión

```bash
# Linux/macOS
cancel HP_LaserJet-123

# Windows (PowerShell)
Remove-PrintJob -PrinterName "HP_LaserJet" -ID 123
```

---

## 🔒 Autenticación

### Guardar Credenciales de Forma Segura

#### Linux
```bash
# Crear archivo de credenciales
echo "username=tu_usuario" > ~/.smbcredentials
echo "password=tu_password" >> ~/.smbcredentials
chmod 600 ~/.smbcredentials

# Usar en URI de impresora
smb://;auth=@IP-SERVIDOR/NOMBRE-IMPRESORA
```

#### Windows
- Administrador de credenciales de Windows guardará automáticamente
- O usa: **Panel de Control > Credenciales de Windows**

---

## 🆘 Soporte

Si tienes problemas:

1. **Verifica conectividad**
   ```bash
   ping IP-SERVIDOR
   telnet IP-SERVIDOR 631  # CUPS
   telnet IP-SERVIDOR 445  # Samba
   ```

2. **Verifica que el servidor esté activo**
   ```bash
   # En el servidor
   sudo systemctl status cups smbd
   ```

3. **Consulta logs del servidor**
   ```bash
   # En el servidor
   sudo tail -f /var/log/cups/error_log
   sudo tail -f /var/log/samba/log.smbd
   ```

4. **Contacta al administrador del sistema**

---

**¡Feliz Impresión! 🖨️**
