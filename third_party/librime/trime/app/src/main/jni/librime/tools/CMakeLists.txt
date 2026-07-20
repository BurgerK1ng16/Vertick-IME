set(EXECUTABLE_OUTPUT_PATH ${PROJECT_BINARY_DIR}/bin)

set(rime_console_deps
  ${rime_library}
  ${rime_dict_library}
  ${rime_gears_library}
  ${rime_levers_library}
  ${rime_plugins_library})

set(rime_api_console_src "rime_api_console.cc" "line_editor.cc")
add_executable(rime_api_console ${rime_api_console_src})
target_link_libraries(rime_api_console ${rime_console_deps})

set(rime_console_src "rime_console.cc")
add_executable(rime_console ${rime_console_src})
target_compile_definitions(rime_console PRIVATE RIME_IMPORTS)
target_link_libraries(rime_console ${rime_console_deps})

set(rime_deployer_src "rime_deployer.cc")
add_executable(rime_deployer ${rime_deployer_src})
target_compile_definitions(rime_deployer PRIVATE RIME_IMPORTS)
target_include_directories(rime_deployer PRIVATE
  ${CMAKE_CURRENT_LIST_DIR}/../src
  ${PROJECT_BINARY_DIR}/librime/src
  ${PROJECT_BINARY_DIR}/librime/deps/glog
  ${CMAKE_CURRENT_LIST_DIR}/../deps/glog/src
  ${CMAKE_CURRENT_LIST_DIR}/../deps/marisa-trie/include
  ${PROJECT_BINARY_DIR}/include
  ${CMAKE_CURRENT_LIST_DIR}/../deps/yaml-cpp/include
  ${CMAKE_CURRENT_LIST_DIR}/../deps/leveldb/include
  ${PROJECT_BINARY_DIR}/snappy
  ${CMAKE_SOURCE_DIR}/snappy
  ${CMAKE_SOURCE_DIR}/OpenCC/src)
file(GLOB RIME_BOOST_INCLUDE_DIRS "${CMAKE_SOURCE_DIR}/boost/libs/*/include")
target_include_directories(rime_deployer PRIVATE ${RIME_BOOST_INCLUDE_DIRS})
target_link_libraries(rime_deployer
  glog_internal
  rime-static
  log)


set(rime_dict_manager_src "rime_dict_manager.cc")
add_executable(rime_dict_manager ${rime_dict_manager_src})
target_link_libraries(rime_dict_manager
  ${rime_library}
  ${rime_dict_library}
  ${rime_levers_library})

set(rime_patch_src "rime_patch.cc")
add_executable(rime_patch ${rime_patch_src})
target_link_libraries(rime_patch
  ${rime_library}
  ${rime_levers_library})

install(TARGETS rime_deployer DESTINATION ${BIN_INSTALL_DIR})
install(TARGETS rime_dict_manager DESTINATION ${BIN_INSTALL_DIR})
install(TARGETS rime_patch DESTINATION ${BIN_INSTALL_DIR})

# do not work with Windows DLL; interfaces to dict are missing DLL export.
if(NOT WIN32 OR NOT BUILD_SHARED_LIBS)
  set(rime_table_decompiler_src "rime_table_decompiler.cc")
  add_executable(rime_table_decompiler ${rime_table_decompiler_src})
  target_compile_definitions(rime_deployer PRIVATE RIME_IMPORTS)
  target_link_libraries(rime_table_decompiler
    ${rime_library}
    ${rime_dict_library})

  install(TARGETS rime_table_decompiler DESTINATION ${BIN_INSTALL_DIR})
endif()

# When embedded under Trime, PROJECT_SOURCE_DIR is the JNI root rather than
# librime itself. Resolve these tool-only sample files from this directory.
set(RIME_MINIMAL_DATA_DIR ${CMAKE_CURRENT_LIST_DIR}/../data/minimal)
file(COPY ${RIME_MINIMAL_DATA_DIR}/default.yaml
     DESTINATION ${EXECUTABLE_OUTPUT_PATH})
file(COPY ${RIME_MINIMAL_DATA_DIR}/symbols.yaml
     DESTINATION ${EXECUTABLE_OUTPUT_PATH})
file(COPY ${RIME_MINIMAL_DATA_DIR}/essay.txt
     DESTINATION ${EXECUTABLE_OUTPUT_PATH})
file(COPY ${RIME_MINIMAL_DATA_DIR}/luna_pinyin.dict.yaml
     DESTINATION ${EXECUTABLE_OUTPUT_PATH})
file(COPY ${RIME_MINIMAL_DATA_DIR}/luna_pinyin.schema.yaml
     DESTINATION ${EXECUTABLE_OUTPUT_PATH})
file(COPY ${RIME_MINIMAL_DATA_DIR}/cangjie5.dict.yaml
     DESTINATION ${EXECUTABLE_OUTPUT_PATH})
file(COPY ${RIME_MINIMAL_DATA_DIR}/cangjie5.schema.yaml
     DESTINATION ${EXECUTABLE_OUTPUT_PATH})
