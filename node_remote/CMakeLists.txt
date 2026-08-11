# node_remote — universal Logos module.
# The Qt plugin glue is generated from src/node_remote_impl.h by logos-module-builder.

cmake_minimum_required(VERSION 3.14)
project(NodeRemoteModulePlugin LANGUAGES CXX)

set(NODE_REMOTE_SOURCES
    src/node_remote_impl.h  src/node_remote_impl.cpp
    src/onion_service.h     src/onion_service.cpp
    src/http_surface.h      src/http_surface.cpp
    src/node_probe.h        src/node_probe.cpp
)

# NOTE: metadata.json's nix.cmake.find_packages / extra_link_libraries are NOT consumed by
# the nix build (skill: builder-metadata-cmake-fields-noop). This guard block is the ONLY
# place extra deps can be declared. logos_module() links only Qt6::Core and
# Qt6::RemoteObjects by default — Qt6::Network happens to come in transitively via
# RemoteObjects, but HttpServer does not.
if(DEFINED ENV{LOGOS_MODULE_BUILDER_ROOT})
    include($ENV{LOGOS_MODULE_BUILDER_ROOT}/cmake/LogosModule.cmake)

    find_package(Qt6 REQUIRED COMPONENTS HttpServer)

    file(READ "${CMAKE_CURRENT_SOURCE_DIR}/metadata.json" METADATA_JSON)
    string(JSON MODULE_NAME GET ${METADATA_JSON} name)

    logos_module(
        NAME ${MODULE_NAME}
        LINK_LIBRARIES Qt6::HttpServer
        SOURCES ${NODE_REMOTE_SOURCES}
    )
    return()
endif()

# Local (non-nix) build path.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/cmake/LogosModule.cmake")
    include(cmake/LogosModule.cmake)
else()
    message(FATAL_ERROR "LogosModule.cmake not found")
endif()

find_package(Qt6 REQUIRED COMPONENTS HttpServer)

file(READ "${CMAKE_CURRENT_SOURCE_DIR}/metadata.json" METADATA_JSON)
string(JSON MODULE_NAME GET ${METADATA_JSON} name)

logos_module(
    NAME ${MODULE_NAME}
    LINK_LIBRARIES Qt6::HttpServer
    SOURCES ${NODE_REMOTE_SOURCES}
)
