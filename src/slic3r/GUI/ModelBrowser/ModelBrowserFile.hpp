#ifndef slic3r_GUI_ModelBrowserFile_hpp_
#define slic3r_GUI_ModelBrowserFile_hpp_

#include <string>

namespace Slic3r::GUI::ModelBrowser {

enum class FileType
{
    Unknown,
    Stl,
    Obj,
    Step,
    ThreeMf,
    Zip,
    Amf,
    Svg,
    Drc
};

enum class ImportAction
{
    ImportGeometry,
    AskProjectOrGeometry,
    InspectArchive,
    SaveOnly
};

FileType detect_file_type(const std::string& path_or_url);
ImportAction default_import_action(FileType file_type);
bool is_likely_download_url(const std::string& url);
bool is_geometry_file_type(FileType file_type);
std::string filename_from_url(const std::string& url, const std::string& fallback = "download");

} // namespace Slic3r::GUI::ModelBrowser

#endif
