<?php
$host = "database-2.cg9kor6sylzn.us-east-1.rds.amazonaws.com";
$user = "pi";
$pass = "Pipipi123";
$db   = "t1";
$port = 3306;

$conn = new mysqli($host, $user, $pass, $db, $port);

if ($conn->connect_error) {
    die("Fallo de conexion: " . $conn->connect_error);
}
?>