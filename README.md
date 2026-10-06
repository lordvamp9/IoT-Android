# IoTClimaRiego

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat&logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)
![Backend](https://img.shields.io/badge/Backend-Raspberry%20Pi-C51A4A?style=flat&logo=raspberrypi&logoColor=white)
![Database](https://img.shields.io/badge/Database-AWS%20RDS%20MySQL-527FFF?style=flat&logo=amazon-aws&logoColor=white)
![Realtime](https://img.shields.io/badge/Realtime-Firebase-FFCA28?style=flat&logo=firebase&logoColor=black)
![Security](https://img.shields.io/badge/Security-ISO%2027400-blue?style=flat)
![Author](https://img.shields.io/badge/Author-vamp9-black?style=flat)

Aplicacion movil Android para monitoreo climatico y gestion de riego automatizado en entornos agricolas e industriales OT. Desarrollada por vamp9.

## Arquitectura del Sistema

- **Cliente Movil:** Aplicacion nativa Android desarrollada en Kotlin con Material Components y ViewBinding.
- **Gateway OT:** Servidor intermedio alojado en Raspberry Pi (Raspbian OS) con servidor web Apache y scripts PHP (`cn.php`, `ingreso.php`).
- **Base de Datos Cloud:** Instancia relacional en AWS RDS (MySQL) para persistencia y auditoria de accesos de usuarios.
- **Telemetria en Tiempo Real:** Integracion con Firebase Realtime Database para monitoreo de sensores (temperatura y humedad) y control de actuadores (bomba de riego y compuerta de ventilacion).
- **Seguridad:** Cumplimiento de lineamientos ISO 27400 mediante control de acceso, aislamiento de credenciales maestras de base de datos en el gateway y uso de sentencias preparadas para prevenir inyecciones SQL.

## Mockups de la Interfaz

<p align="center">
  <img src="docs/images/login_mockup.png" alt="Pantalla de Autenticacion" width="300"/>
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/images/dashboard_mockup.png" alt="Panel de Control OT" width="300"/>
</p>

## Componentes y Tecnologias

- Android Studio
- Kotlin
- Android Volley
- Firebase Realtime Database
- Raspberry Pi (Raspbian OS)
- Apache HTTP Server y PHP
- AWS RDS MySQL
- MySQL Workbench
