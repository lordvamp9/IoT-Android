<?php
header("Content-Type: text/plain; charset=UTF-8");
include 'cn.php';

$usuario  = $_POST['usuario'] ?? $_GET['usuario'] ?? '';
$password = $_POST['password'] ?? $_GET['password'] ?? '';

if (!empty($usuario) && !empty($password)) {
    $stmt = $conn->prepare("INSERT INTO usuario (usuario, password) VALUES (?, ?)");
    $stmt->bind_param("ss", $usuario, $password);

    if ($stmt->execute()) {
        echo "registro insertado";
    } else {
        echo "error al insertar: " . $stmt->error;
    }
    $stmt->close();
} else {
    echo "datos vacios";
}

$conn->close();
?>