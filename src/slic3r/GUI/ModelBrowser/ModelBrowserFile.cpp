#include "ModelBrowserFile.hpp"

#include <algorithm>
#include <cctype>
#include <utility>

namespace Slic3r::GUI::ModelBrowser {
namespace {

std::string strip_query_and_fragment(std::string value)
{
    const size_t query = value.find_first_of("?#");
    if (query != std::string::npos)
        value.erase(query);
    return value;
}

std::string basename_from_path(std::string value)
{
    value = strip_query_and_fragment(std::move(value));
    const size_t slash = value.find_last_of("/\\");
    if (slash != std::string::npos)
        value.erase(0, slash + 1);
    return value;
}

std::string lowercase_ascii(std::string value)
{
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

std::string extension_from_path_or_url(const std::string& path_or_url)
{
    std::string filename = basename_from_path(path_or_url);
    const size_t dot = filename.find_last_of('.');
    if (dot == std::string::npos)
        return {};
    return lowercase_ascii(filename.substr(dot));
}

} // namespace

FileType detect_file_type(const std::string& path_or_url)
{
    const std::string ext = extension_from_path_or_url(path_or_url);
    if (ext == ".stl")
        return FileType::Stl;
    if (ext == ".obj")
        return FileType::Obj;
    if (ext == ".stp" || ext == ".step")
        return FileType::Step;
    if (ext == ".3mf")
        return FileType::ThreeMf;
    if (ext == ".zip")
        return FileType::Zip;
    if (ext == ".amf")
        return FileType::Amf;
    if (ext == ".svg")
        return FileType::Svg;
    if (ext == ".drc")
        return FileType::Drc;
    return FileType::Unknown;
}

ImportAction default_import_action(FileType file_type)
{
    switch (file_type) {
    case FileType::Stl:
    case FileType::Obj:
    case FileType::Step:
    case FileType::Amf:
    case FileType::Svg:
    case FileType::Drc:
        return ImportAction::ImportGeometry;
    case FileType::ThreeMf:
        return ImportAction::AskProjectOrGeometry;
    case FileType::Zip:
        return ImportAction::InspectArchive;
    case FileType::Unknown:
        return ImportAction::SaveOnly;
    }
    return ImportAction::SaveOnly;
}

bool is_likely_download_url(const std::string& url)
{
    return detect_file_type(url) != FileType::Unknown;
}

bool is_geometry_file_type(FileType file_type)
{
    return default_import_action(file_type) == ImportAction::ImportGeometry;
}

std::string filename_from_url(const std::string& url, const std::string& fallback)
{
    std::string filename = basename_from_path(url);
    if (filename.empty() || filename == "." || filename == "..")
        return fallback;
    return filename;
}

} // namespace Slic3r::GUI::ModelBrowser
