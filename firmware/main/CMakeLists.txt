idf_component_register(
    SRCS "app_main.cpp" "board.cpp" "network.cpp" "config_store.cpp" "provision_usb.cpp" "screen.cpp"
         "display.cpp" "frame.cpp"
    INCLUDE_DIRS "."
    REQUIRES u8g2_st7305 esp_timer esp_driver_gpio esp_wifi esp_netif esp_event nvs_flash esp_psram
             json esp_http_client mbedtls esp_driver_usb_serial_jtag esp_app_format
)
