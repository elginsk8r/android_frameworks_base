/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "idmap2d/Idmap2Service.h"

#include <sys/stat.h>   // umask
#include <sys/types.h>  // umask
#include <sys/wait.h>   // waitpid
#include <unistd.h>     // fork, execv

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <ostream>
#include <string>
#include <utility>
#include <vector>

#include "android-base/file.h"
#include "android-base/macros.h"
#include "android-base/scopeguard.h"
#include "android-base/stringprintf.h"
#include "binder/IPCThreadState.h"
#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "idmap2/PrettyPrintVisitor.h"
#include "idmap2/Result.h"
#include "idmap2/SysTrace.h"
#include <fcntl.h>
#include <ziparchive/zip_archive.h>

using android::base::StringPrintf;
using android::binder::Status;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::FabricatedOverlayContainer;
using android::idmap2::Idmap;
using android::idmap2::IdmapConstraint;
using android::idmap2::IdmapConstraints;
using android::idmap2::IdmapHeader;
using android::idmap2::OverlayResourceContainer;
using android::idmap2::PrettyPrintVisitor;
using android::idmap2::TargetResourceContainer;
using android::idmap2::utils::kIdmapCacheDir;
using android::idmap2::utils::kIdmapFilePermissionMask;
using android::idmap2::utils::RandomStringForPath;
using android::idmap2::utils::UidHasWriteAccessToPath;

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;

namespace {

constexpr std::string_view kFrameworkPath = "/system/framework/framework-res.apk";
constexpr std::string_view kLineagePath = "/system/framework/org.lineageos.platform-res.apk";

Status ok() {
  return Status::ok();
}

Status error(const std::string& msg) {
  LOG(ERROR) << msg;
  return Status::fromExceptionCode(Status::EX_NONE, msg.c_str());
}

// Unlike error(), reports the message to the caller, which needs the aapt2 diagnostics.
Status compile_error(const std::string& msg) {
  constexpr size_t kMaxMessageLength = 32 * 1024;
  LOG(ERROR) << msg;
  return Status::fromServiceSpecificError(1, msg.substr(0, kMaxMessageLength).c_str());
}

PolicyBitmask ConvertAidlArgToPolicyBitmask(int32_t arg) {
  return static_cast<PolicyBitmask>(arg);
}

std::unique_ptr<const IdmapConstraints> ConvertAidlConstraintsToIdmapConstraints(
        const std::vector<android::os::OverlayConstraint>& constraints) {
  auto idmapConstraints = std::make_unique<IdmapConstraints>();
  for (const auto& constraint : constraints) {
    IdmapConstraint idmapConstraint{};
    idmapConstraint.constraint_type = constraint.type;
    idmapConstraint.constraint_value = constraint.value;
    idmapConstraints->constraints.insert(idmapConstraint);
  }
  return idmapConstraints;
}

// Compiled overlays are stored next to fabricated overlays as "<package>-<name>-<suffix>.apk".
constexpr std::string_view kCompiledOverlayExtension = ".apk";
constexpr std::string_view kAapt2Path = "/system/bin/aapt2";
// Package the resources of compiled overlays are linked as. The manifest package is renamed to
// the owning package afterwards, which may be "android" and must not clash with framework-res.
constexpr std::string_view kCompiledOverlayLinkPackage = "org.lineageos.theme.compiled";

bool IsValidName(const std::string& name) {
  if (name.empty()) {
    return false;
  }
  for (const char c : name) {
    if (!isalnum(c) && c != '_' && c != '.') {
      return false;
    }
  }
  return true;
}

bool IsCompiledOverlay(const std::filesystem::path& path) {
  const std::string file_name = path.filename();
  return path.extension() == kCompiledOverlayExtension &&
         std::count(file_name.begin(), file_name.end(), '-') == 2;
}

// Runs aapt2 and returns its output, which is also the error message if it fails.
android::idmap2::Result<std::string> RunAapt2(const std::vector<std::string>& args) {
  int pipe_fds[2];
  if (pipe2(pipe_fds, O_CLOEXEC) != 0) {
    return android::idmap2::Error("pipe failed: %s", strerror(errno));
  }

  std::vector<char*> argv;
  argv.push_back(const_cast<char*>(kAapt2Path.data()));
  for (const auto& arg : args) {
    argv.push_back(const_cast<char*>(arg.c_str()));
  }
  argv.push_back(nullptr);

  const pid_t pid = fork();
  if (pid < 0) {
    close(pipe_fds[0]);
    close(pipe_fds[1]);
    return android::idmap2::Error("fork failed: %s", strerror(errno));
  }
  if (pid == 0) {
    dup2(pipe_fds[1], STDOUT_FILENO);
    dup2(pipe_fds[1], STDERR_FILENO);
    execv(kAapt2Path.data(), argv.data());
    _exit(127);
  }

  close(pipe_fds[1]);
  std::string output;
  android::base::ReadFdToString(pipe_fds[0], &output);
  close(pipe_fds[0]);

  int status;
  if (TEMP_FAILURE_RETRY(waitpid(pid, &status, 0)) != pid) {
    return android::idmap2::Error("waitpid failed: %s", strerror(errno));
  }
  if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
    return android::idmap2::Error("aapt2 %s failed:\n%s", args[0].c_str(), output.c_str());
  }
  return output;
}

// Extracts the res/ tree from the zip archive in fd into dir.
android::idmap2::Result<android::idmap2::Unit> ExtractSources(int fd, const std::string& dir) {
  ZipArchiveHandle handle;
  // The fd belongs to the parcel, give the archive its own copy.
  if (const int32_t err = OpenArchiveFd(dup(fd), "sources", &handle, true /* assume_ownership */);
      err != 0) {
    return android::idmap2::Error("failed to open sources: %s", ErrorCodeString(err));
  }
  auto close_archive = android::base::make_scope_guard([&] { CloseArchive(handle); });

  void* cookie;
  if (const int32_t err = StartIteration(handle, &cookie); err != 0) {
    return android::idmap2::Error("failed to iterate sources: %s", ErrorCodeString(err));
  }
  auto end_iteration = android::base::make_scope_guard([&] { EndIteration(cookie); });

  ZipEntry64 entry;
  std::string name;
  int32_t result;
  while ((result = Next(cookie, &entry, &name)) == 0) {
    if (name.empty() || name.back() == '/') {
      continue;
    }
    if (!name.starts_with("res/") || name.find("..") != std::string::npos ||
        name.find("//") != std::string::npos) {
      return android::idmap2::Error("invalid source entry '%s'", name.c_str());
    }
    const std::filesystem::path path = std::filesystem::path(dir) / name;
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    if (ec) {
      return android::idmap2::Error("failed to create %s: %s", path.parent_path().c_str(),
                                    ec.message().c_str());
    }
    android::base::unique_fd out(
        open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644));
    if (out.get() < 0) {
      return android::idmap2::Error("failed to create %s: %s", path.c_str(), strerror(errno));
    }
    if (const int32_t err = ExtractEntryToFile(handle, &entry, out.get()); err != 0) {
      return android::idmap2::Error("failed to extract %s: %s", name.c_str(),
                                    ErrorCodeString(err));
    }
  }
  if (result != -1) {
    return android::idmap2::Error("failed to iterate sources: %s", ErrorCodeString(result));
  }
  return android::idmap2::Unit{};
}

// Reads the info of a compiled overlay from its path, see Idmap2Service::createCompiledOverlay.
android::idmap2::Result<android::os::FabricatedOverlayInfo> ReadCompiledOverlayInfo(
    const std::string& path) {
  const std::string stem = std::filesystem::path(path).stem();
  const size_t first = stem.find('-');
  const size_t last = stem.rfind('-');
  if (first == std::string::npos || first == last) {
    return android::idmap2::Error("malformed compiled overlay name '%s'", path.c_str());
  }
  const std::string package_name = stem.substr(0, first);
  const std::string overlay_name = stem.substr(first + 1, last - first - 1);

  const auto container = OverlayResourceContainer::FromPath(path);
  if (!container) {
    return android::idmap2::Error(container.GetError(), "failed to open '%s'", path.c_str());
  }
  const auto info = (*container)->FindOverlayInfo(overlay_name);
  if (!info) {
    return android::idmap2::Error(info.GetError(), "failed to read '%s'", path.c_str());
  }
  if (info->package_name != package_name) {
    return android::idmap2::Error("package of '%s' is '%s'", path.c_str(),
                                  info->package_name.c_str());
  }

  android::os::FabricatedOverlayInfo out_info;
  out_info.packageName = package_name;
  out_info.overlayName = overlay_name;
  out_info.targetPackageName = info->target_package;
  out_info.targetOverlayable = info->target_name;
  out_info.path = path;
  return out_info;
}

}  // namespace

namespace android::os {

template <typename T>
const T* Idmap2Service::GetPointer(const OwningPtr<T>& ptr) {
    return std::visit([](auto&& ptr) { return ptr.get(); }, ptr);
}

Status Idmap2Service::getIdmapPath(const std::string& overlay_path,
                                   int32_t user_id ATTRIBUTE_UNUSED, std::string* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::getIdmapPath " << overlay_path;
  *_aidl_return = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  return ok();
}

Status Idmap2Service::removeIdmap(const std::string& overlay_path, int32_t user_id ATTRIBUTE_UNUSED,
                                  bool* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::removeIdmap " << overlay_path;
  const uid_t uid = IPCThreadState::self()->getCallingUid();
  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  if (!UidHasWriteAccessToPath(uid, idmap_path)) {
    *_aidl_return = false;
    return error(base::StringPrintf("failed to unlink %s: calling uid %d lacks write access",
                                    idmap_path.c_str(), uid));
  }
  if (unlink(idmap_path.c_str()) != 0) {
    *_aidl_return = false;
    return error("failed to unlink " + idmap_path + ": " + strerror(errno));
  }
  *_aidl_return = true;
  return ok();
}

Status Idmap2Service::verifyIdmap(const std::string& target_path, const std::string& overlay_path,
                                  const std::string& overlay_name, int32_t fulfilled_policies,
                                  bool enforce_overlayable, int32_t user_id ATTRIBUTE_UNUSED,
                                  const std::vector<os::OverlayConstraint>& constraints,
                                  bool* _aidl_return) {
  SYSTRACE << "Idmap2Service::verifyIdmap " << overlay_path;
  assert(_aidl_return);

  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  std::ifstream fin(idmap_path);
  const std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(fin);
  const std::unique_ptr<const IdmapConstraints> oldConstraints =
          IdmapConstraints::FromBinaryStream(fin);
  fin.close();
  if (!header) {
    *_aidl_return = false;
    LOG(WARNING) << "failed to parse idmap header of '" << idmap_path << "'";
    return ok();
  }
  if (!oldConstraints) {
    *_aidl_return = false;
    LOG(WARNING) << "failed to parse idmap constraints of '" << idmap_path << "'";
    return ok();
  }

  const auto target = GetTargetContainer(target_path);
  if (!target) {
    *_aidl_return = false;
    LOG(WARNING) << "failed to load target '" << target_path << "'";
    return ok();
  }

  const auto overlay = OverlayResourceContainer::FromPath(overlay_path);
  if (!overlay) {
    *_aidl_return = false;
    LOG(WARNING) << "failed to load overlay '" << overlay_path << "'";
    return ok();
  }

  auto up_to_date =
      header->IsUpToDate(*GetPointer(*target), **overlay, overlay_name,
                         ConvertAidlArgToPolicyBitmask(fulfilled_policies), enforce_overlayable);

  std::unique_ptr<const IdmapConstraints> newConstraints =
          ConvertAidlConstraintsToIdmapConstraints(constraints);

  *_aidl_return = static_cast<bool>(up_to_date && (*oldConstraints == *newConstraints));
  if (!up_to_date) {
    LOG(WARNING) << "idmap '" << idmap_path
                 << "' not up to date : " << up_to_date.GetErrorMessage();
  }
  return ok();
}

Status Idmap2Service::createIdmap(const std::string& target_path, const std::string& overlay_path,
                                  const std::string& overlay_name, int32_t fulfilled_policies,
                                  bool enforce_overlayable, int32_t user_id ATTRIBUTE_UNUSED,
                                  const std::vector<os::OverlayConstraint>& constraints,
                                  std::optional<std::string>* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::createIdmap " << target_path << " " << overlay_path;
  _aidl_return->reset();

  const PolicyBitmask policy_bitmask = ConvertAidlArgToPolicyBitmask(fulfilled_policies);

  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  const uid_t uid = IPCThreadState::self()->getCallingUid();
  if (!UidHasWriteAccessToPath(uid, idmap_path)) {
    return error(base::StringPrintf("will not write to %s: calling uid %d lacks write accesss",
                                    idmap_path.c_str(), uid));
  }

  // idmap files are mapped with mmap in libandroidfw. Deleting and recreating the idmap guarantees
  // that existing memory maps will continue to be valid and unaffected. The file must be deleted
  // before attempting to create the idmap, so that if idmap  creation fails, the overlay will no
  // longer be usable.
  unlink(idmap_path.c_str());

  const auto target = GetTargetContainer(target_path);
  if (!target) {
    return error("failed to load target '%s'" + target_path);
  }

  const auto overlay = OverlayResourceContainer::FromPath(overlay_path);
  if (!overlay) {
    return error("failed to load apk overlay '%s'" + overlay_path);
  }

  std::unique_ptr<const IdmapConstraints> idmapConstraints =
          ConvertAidlConstraintsToIdmapConstraints(constraints);
  const auto idmap = Idmap::FromContainers(*GetPointer(*target), **overlay, overlay_name,
                                           policy_bitmask, enforce_overlayable,
                                           std::move(idmapConstraints));
  if (!idmap) {
    return error(idmap.GetErrorMessage());
  }

  umask(kIdmapFilePermissionMask);
  std::ofstream fout(idmap_path);
  if (fout.fail()) {
    return error("failed to open idmap path " + idmap_path);
  }

  BinaryStreamVisitor visitor(fout);
  (*idmap)->accept(&visitor);
  fout.close();
  if (fout.fail()) {
    unlink(idmap_path.c_str());
    return error("failed to write to idmap path " + idmap_path);
  }

  *_aidl_return = idmap_path;
  return ok();
}

Status Idmap2Service::verifyOrCreateIdmap(const os::IdmapParams& params,
                                          std::optional<std::string>* _aidl_return) {
  bool verified = false;
  verifyIdmap(params.targetPath, params.overlayPath, params.overlayName,
              params.fulfilledPolicies, params.enforceOverlayable, params.userId,
              params.constraints, &verified);
  if (verified) {
      *_aidl_return = "";
      return ok();
  }

  return createIdmap(params.targetPath, params.overlayPath, params.overlayName,
                     params.fulfilledPolicies, params.enforceOverlayable, params.userId,
                     params.constraints, _aidl_return);
}

Status Idmap2Service::verifyOrCreateIdmaps(const std::vector<os::IdmapParams>& params,
                                           std::vector<std::string>* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::verifyOrCreateIdmaps";

  std::vector<std::string> idmap_paths;
  for (const auto& param : params) {
      std::optional<std::string> idmap_path;
      verifyOrCreateIdmap(param, &idmap_path);

      /*
       * There are three possible idmap_path values:
       * - empty ("") -> existing idmap was verified
       * - non-empty  -> idmap was created
       * - nullptr    -> idmap could not be created
       * We need to append a value for the third case so the caller has a result for each input.
       */
      idmap_paths.emplace_back(std::move(idmap_path).value_or("INVALID"));
  }
  *_aidl_return = idmap_paths;
  return ok();
}

idmap2::Result<Idmap2Service::TargetResourceContainerPtr> Idmap2Service::GetTargetContainer(
    const std::string& target_path) {
  const bool is_framework = target_path == kFrameworkPath;
  const bool is_lineage_framework = target_path == kLineagePath;
  bool use_cache;
  struct stat st = {};
  if (is_framework || is_lineage_framework || !::stat(target_path.c_str(), &st)) {
    use_cache = true;
  } else {
    LOG(WARNING) << "failed to stat target path '" << target_path << "' for the cache";
    use_cache = false;
  }

  if (use_cache) {
    std::lock_guard lock(container_cache_mutex_);
    if (auto cache_it = container_cache_.find(target_path); cache_it != container_cache_.end()) {
      const auto& item = cache_it->second;
      if (is_framework ||
        (item.dev == st.st_dev && item.inode == st.st_ino && item.size == st.st_size
          && item.mtime.tv_sec == st.st_mtim.tv_sec && item.mtime.tv_nsec == st.st_mtim.tv_nsec)) {
        return {item.apk};
      }
      container_cache_.erase(cache_it);
    }
  }

  auto target = TargetResourceContainer::FromPath(target_path);
  if (!target) {
    return target.GetError();
  }
  if (!use_cache) {
    return {std::move(*target)};
  }

  auto res = std::shared_ptr(std::move(*target));
  std::lock_guard lock(container_cache_mutex_);
  container_cache_.emplace(target_path, CachedContainer {
    .dev = dev_t(st.st_dev),
    .inode = ino_t(st.st_ino),
    .size = st.st_size,
    .mtime = st.st_mtim,
    .apk = res
  });
  return {res};
}

Status Idmap2Service::createFabricatedOverlay(
    const os::FabricatedOverlayInternal& overlay,
    std::optional<os::FabricatedOverlayInfo>* _aidl_return) {
  idmap2::FabricatedOverlay::Builder builder(overlay.packageName, overlay.overlayName,
                                             overlay.targetPackageName);
  if (!overlay.targetOverlayable.empty()) {
    builder.SetOverlayable(overlay.targetOverlayable);
  }

  for (const auto& res : overlay.entries) {
    if (res.dataType == Res_value::TYPE_STRING) {
      builder.SetResourceValue(res.resourceName, res.dataType, res.stringData.value(),
            res.configuration.value_or(std::string()));
    } else if (res.binaryData.has_value()) {
      builder.SetResourceValue(res.resourceName, res.binaryData->get(),
                               res.binaryDataOffset, res.binaryDataSize,
                               res.configuration.value_or(std::string()),
                               res.isNinePatch);
    } else {
      builder.SetResourceValue(res.resourceName, res.dataType, res.data,
            res.configuration.value_or(std::string()));
    }
  }

  // Generate the file path of the fabricated overlay and ensure it does not collide with an
  // existing path. Re-registering a fabricated overlay will always result in an updated path.
  std::string path;
  std::string file_name;
  do {
    constexpr size_t kSuffixLength = 4;
    const std::string random_suffix = RandomStringForPath(kSuffixLength);
    file_name = StringPrintf("%s-%s-%s.frro", overlay.packageName.c_str(),
                             overlay.overlayName.c_str(), random_suffix.c_str());
    path = StringPrintf("%s/%s", kIdmapCacheDir.data(), file_name.c_str());

    // Invoking std::filesystem::exists with a file name greater than 255 characters will cause this
    // process to abort since the name exceeds the maximum file name size.
    const size_t kMaxFileNameLength = 255;
    if (file_name.size() > kMaxFileNameLength) {
      return error(
          base::StringPrintf("fabricated overlay file name '%s' longer than %zu characters",
                             file_name.c_str(), kMaxFileNameLength));
    }
  } while (std::filesystem::exists(path));
  builder.setFrroPath(path);

  const uid_t uid = IPCThreadState::self()->getCallingUid();
  if (!UidHasWriteAccessToPath(uid, path)) {
    return error(base::StringPrintf("will not write to %s: calling uid %d lacks write access",
                                    path.c_str(), uid));
  }

  const auto frro = builder.Build();
  if (!frro) {
    return error(StringPrintf("failed to serialize '%s:%s': %s", overlay.packageName.c_str(),
                              overlay.overlayName.c_str(), frro.GetErrorMessage().c_str()));
  }
  // Persist the fabricated overlay.
  umask(kIdmapFilePermissionMask);
  std::ofstream fout(path);
  if (fout.fail()) {
    return error("failed to open frro path " + path);
  }
  auto result = frro->ToBinaryStream(fout);
  if (!result) {
    unlink(path.c_str());
    return error("failed to write to frro path " + path + ": " + result.GetErrorMessage());
  }
  if (fout.fail()) {
    unlink(path.c_str());
    return error("failed to write to frro path " + path);
  }

  os::FabricatedOverlayInfo out_info;
  out_info.packageName = overlay.packageName;
  out_info.overlayName = overlay.overlayName;
  out_info.targetPackageName = overlay.targetPackageName;
  out_info.targetOverlayable = overlay.targetOverlayable;
  out_info.path = path;
  *_aidl_return = out_info;
  return ok();
}

Status Idmap2Service::acquireFabricatedOverlayIterator(int32_t* _aidl_return) {
  std::lock_guard l(frro_iter_mutex_);
  if (frro_iter_.has_value()) {
    LOG(WARNING) << "active ffro iterator was not previously released";
  }
  frro_iter_ = std::filesystem::directory_iterator(kIdmapCacheDir);
  if (frro_iter_id_ == std::numeric_limits<int32_t>::max()) {
    frro_iter_id_ = 0;
  } else {
    ++frro_iter_id_;
  }
  *_aidl_return = frro_iter_id_;
  return ok();
}

Status Idmap2Service::releaseFabricatedOverlayIterator(int32_t iteratorId) {
  std::lock_guard l(frro_iter_mutex_);
  if (!frro_iter_.has_value()) {
    LOG(WARNING) << "no active ffro iterator to release";
  } else if (frro_iter_id_ != iteratorId) {
    LOG(WARNING) << "incorrect iterator id in a call to release";
  } else {
    frro_iter_.reset();
  }
  return ok();
}

Status Idmap2Service::nextFabricatedOverlayInfos(int32_t iteratorId,
    std::vector<os::FabricatedOverlayInfo>* _aidl_return) {
  std::lock_guard l(frro_iter_mutex_);

  constexpr size_t kMaxEntryCount = 100;
  if (!frro_iter_.has_value()) {
    return error("no active frro iterator");
  } else if (frro_iter_id_ != iteratorId) {
    return error("incorrect iterator id in a call to next");
  }

  size_t count = 0;
  auto& entry_iter = *frro_iter_;
  auto entry_iter_end = end(*frro_iter_);
  for (; entry_iter != entry_iter_end && count < kMaxEntryCount; ++entry_iter) {
    auto& entry = *entry_iter;
    if (entry.is_regular_file() && IsCompiledOverlay(entry.path())) {
      auto info = ReadCompiledOverlayInfo(entry.path().native());
      if (!info) {
        LOG(WARNING) << info.GetErrorMessage();
        continue;
      }
      _aidl_return->emplace_back(std::move(*info));
      count++;
      continue;
    }
    if (!entry.is_regular_file() || !android::IsFabricatedOverlay(entry.path().native())) {
      continue;
    }

    const auto overlay = FabricatedOverlayContainer::FromPath(entry.path().native());
    if (!overlay) {
      LOG(WARNING) << "Failed to open '" << entry.path() << "': " << overlay.GetErrorMessage();
      continue;
    }

    auto info = (*overlay)->GetManifestInfo();
    os::FabricatedOverlayInfo out_info;
    out_info.packageName = std::move(info.package_name);
    out_info.overlayName = std::move(info.name);
    out_info.targetPackageName = std::move(info.target_package);
    out_info.targetOverlayable = std::move(info.target_name);
    out_info.path = entry.path();
    _aidl_return->emplace_back(std::move(out_info));
    count++;
  }
  return ok();
}

binder::Status Idmap2Service::deleteFabricatedOverlay(const std::string& overlay_path,
                                                      bool* _aidl_return) {
  SYSTRACE << "Idmap2Service::deleteFabricatedOverlay " << overlay_path;
  const uid_t uid = IPCThreadState::self()->getCallingUid();

  if (!UidHasWriteAccessToPath(uid, overlay_path)) {
    *_aidl_return = false;
    return error(base::StringPrintf("failed to unlink %s: calling uid %d lacks write access",
                                    overlay_path.c_str(), uid));
  }

  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  if (!UidHasWriteAccessToPath(uid, idmap_path)) {
    *_aidl_return = false;
    return error(base::StringPrintf("failed to unlink %s: calling uid %d lacks write access",
                                    idmap_path.c_str(), uid));
  }

  if (unlink(overlay_path.c_str()) != 0) {
    *_aidl_return = false;
    return error("failed to unlink " + overlay_path + ": " + strerror(errno));
  }

  if (unlink(idmap_path.c_str()) != 0) {
    *_aidl_return = false;
    return error("failed to unlink " + idmap_path + ": " + strerror(errno));
  }

  *_aidl_return = true;
  return ok();
}

Status Idmap2Service::createCompiledOverlay(
    const std::string& package_name, const std::string& overlay_name,
    const std::string& target_package_name, const os::ParcelFileDescriptor& sources,
    std::optional<os::FabricatedOverlayInfo>* _aidl_return) {
  SYSTRACE << "Idmap2Service::createCompiledOverlay " << package_name << ":" << overlay_name;
  if (!IsValidName(package_name) || !IsValidName(overlay_name) ||
      !IsValidName(target_package_name)) {
    return compile_error(StringPrintf("invalid compiled overlay '%s:%s' for '%s'", package_name.c_str(),
                              overlay_name.c_str(), target_package_name.c_str()));
  }

  // Like fabricated overlays, re-registering a compiled overlay results in an updated path.
  std::string path;
  do {
    constexpr size_t kSuffixLength = 4;
    const std::string file_name =
        StringPrintf("%s-%s-%s%s", package_name.c_str(), overlay_name.c_str(),
                     RandomStringForPath(kSuffixLength).c_str(), kCompiledOverlayExtension.data());
    constexpr size_t kMaxFileNameLength = 255;
    if (file_name.size() > kMaxFileNameLength) {
      return compile_error(StringPrintf("compiled overlay file name '%s' longer than %zu characters",
                                file_name.c_str(), kMaxFileNameLength));
    }
    path = StringPrintf("%s/%s", kIdmapCacheDir.data(), file_name.c_str());
  } while (std::filesystem::exists(path));

  const uid_t uid = IPCThreadState::self()->getCallingUid();
  if (!UidHasWriteAccessToPath(uid, path)) {
    return compile_error(StringPrintf("will not write to %s: calling uid %d lacks write access",
                              path.c_str(), uid));
  }

  std::string work_dir = StringPrintf("%s/compile-XXXXXX", kIdmapCacheDir.data());
  if (mkdtemp(work_dir.data()) == nullptr) {
    return compile_error(StringPrintf("failed to create %s: %s", work_dir.c_str(), strerror(errno)));
  }
  auto remove_work_dir = base::make_scope_guard([&] {
    std::error_code ec;
    std::filesystem::remove_all(work_dir, ec);
  });

  if (const auto result = ExtractSources(sources.get(), work_dir); !result) {
    return compile_error(result.GetErrorMessage());
  }

  const std::string manifest_path = work_dir + "/AndroidManifest.xml";
  {
    std::ofstream manifest(manifest_path);
    manifest << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             << "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
             << "package=\"" << kCompiledOverlayLinkPackage << "\">\n"
             << "    <overlay android:name=\"" << overlay_name << "\" "
             << "android:targetPackage=\"" << target_package_name << "\" />\n"
             << "    <application android:hasCode=\"false\" />\n"
             << "</manifest>\n";
    if (manifest.fail()) {
      return compile_error("failed to write " + manifest_path);
    }
  }

  const std::string compiled_path = work_dir + "/compiled.zip";
  if (const auto result = RunAapt2({"compile", "--legacy", "--dir", work_dir + "/res", "-o",
                                    compiled_path});
      !result) {
    return compile_error(result.GetErrorMessage());
  }

  const std::string apk_path = work_dir + "/overlay.apk";
  if (const auto result = RunAapt2({"link", "-o", apk_path, "-I", std::string(kFrameworkPath),
                                    "--manifest", manifest_path, "--rename-manifest-package",
                                    package_name, "--min-sdk-version", "29", compiled_path});
      !result) {
    return compile_error(result.GetErrorMessage());
  }

  if (rename(apk_path.c_str(), path.c_str()) != 0) {
    return compile_error(StringPrintf("failed to move overlay to %s: %s", path.c_str(), strerror(errno)));
  }
  chmod(path.c_str(), 0644);

  auto info = ReadCompiledOverlayInfo(path);
  if (!info) {
    unlink(path.c_str());
    return compile_error(info.GetErrorMessage());
  }
  *_aidl_return = std::move(*info);
  return ok();
}

binder::Status Idmap2Service::dumpIdmap(const std::string& overlay_path,
                                        std::string* _aidl_return) {
  assert(_aidl_return);

  const auto idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  std::ifstream fin(idmap_path);
  const auto idmap = Idmap::FromBinaryStream(fin);
  fin.close();
  if (!idmap) {
    return error(idmap.GetErrorMessage());
  }

  std::stringstream stream;
  PrettyPrintVisitor visitor(stream);
  (*idmap)->accept(&visitor);
  *_aidl_return = stream.str();

  return ok();
}

}  // namespace android::os
