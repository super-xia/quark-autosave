package com.quarksave.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 夸克网盘 API 封装（移植自 Cp0204/quark-auto-save）。
 * 转存链路：stoken -> 分享详情 -> 目标目录fid -> save -> 轮询任务。
 */
public class QuarkApi {
    private static final String BASE_URL = "https://drive-pc.quark.cn";
    private static final String BASE_URL_MOBILE = "https://drive-m.quark.cn";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/3.14.2 Chrome/112.0.5615.165 Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch";
    private static final String USER_AGENT_MOBILE = "Mozilla/5.0 (Linux; Android 11; M2011K2C Build/RKQ1.200928.002) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.5615.165 Mobile Safari/537.36 quark-cloud-drive/7.4.5.680";

    private String cookie;
    private final StringBuilder log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String mKps = "", mSign = "", mVcode = "";

    public interface LogListener {
        void onLog(String line);
    }
    private LogListener logListener;

    public void setLogListener(LogListener l) { this.logListener = l; }

    private void emit(String line) {
        log.append(line).append("\n");
        if (logListener != null && Looper.myLooper() == Looper.getMainLooper()) {
            logListener.onLog(line);
        }
    }
    private void emitAsync(final String line) {
        log.append(line).append("\n");
        if (logListener != null) {
            main.post(new Runnable() { public void run() { logListener.onLog(line); } });
        }
    }

    public QuarkApi(String cookie) {
        this.cookie = cookie == null ? "" : cookie.trim();
        this.log = new StringBuilder();
        mKps = matchCookie("kps");
        mSign = matchCookie("sign");
        mVcode = matchCookie("vcode");
    }
    public String getCookie() { return cookie; }

    private String matchCookie(String key) {
        if (cookie == null || cookie.isEmpty()) return "";
        Matcher m = Pattern.compile("(?<!\\w)" + key + "=([a-zA-Z0-9%+/=]+)[;&]?").matcher(cookie);
        if (m.find()) return m.group(1).replace("%25", "%");
        return "";
    }

    private boolean hasMparam() {
        return !mKps.isEmpty() && !mSign.isEmpty() && !mVcode.isEmpty();
    }

    // ---------- low level ----------
    private HttpURLConnection open(String method, String url) throws Exception {
        boolean isShareMobile = hasMparam() && url.contains("share") && url.contains(BASE_URL);
        String useBase = isShareMobile ? BASE_URL_MOBILE : BASE_URL;
        String finalUrl = url;
        if (isShareMobile) finalUrl = url.replace(BASE_URL, useBase);

        HttpURLConnection c = (HttpURLConnection) new URL(finalUrl).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", USER_AGENT);
        if (isShareMobile) {
            // 手机版 share 接口不带 Cookie，改用签名参数（与仓库一致）
            String sep = finalUrl.contains("?") ? "&" : "?";
            String query = sep + "device_model=M2011K2C&entry=default_clouddrive&_t_group=0%3A_s_vp%3A1"
                    + "&dmn=Mi%2B11&fr=android&pf=3300&bi=35937&ve=7.4.5.680&ss=411x875"
                    + "&mi=M2011K2C&nt=5&nw=0&kt=4&pr=ucpro&sv=release&dt=phone&data_from=ucapi"
                    + "&kps=" + urlenc(mKps) + "&sign=" + urlenc(mSign) + "&vcode=" + urlenc(mVcode)
                    + "&app=clouddrive&kkkk=1";
            String redirect = finalUrl + query;
            // HttpURLConnection 不允许在 openConnection 后改 URL，重建
            c.disconnect();
            c = (HttpURLConnection) new URL(redirect).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", USER_AGENT_MOBILE);
        } else if (cookie != null && cookie.length() > 0) {
            c.setRequestProperty("Cookie", cookie);
        }
        return c;
    }

    private JSONObject req(String method, String url, JSONObject payload) throws Exception {
        HttpURLConnection c = open(method, url);
        if (payload != null) {
            c.setDoOutput(true);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        JSONObject j = new JSONObject(sb.toString());
        if (code >= 300) j.put("_http", code);
        return j;
    }

    // ---------- account ----------
    public String getAccountNickname() {
        try {
            JSONObject r = req("GET", "https://pan.quark.cn/account/info?fr=pc&platform=pc", null);
            if (r.has("data") && !r.isNull("data")) return r.getJSONObject("data").getString("nickname");
        } catch (Exception e) { }
        return null;
    }

    // ---------- share ----------
    public static String[] extractUrl(String url) {
        String pwd_id = null;
        Matcher m = Pattern.compile("/s/(\\w+)").matcher(url);
        if (m.find()) pwd_id = m.group(1);
        String passcode = "";
        Matcher m2 = Pattern.compile("pwd=(\\w+)").matcher(url);
        if (m2.find()) passcode = m2.group(1);
        // 取分享子目录 fid（32位hex或其它）
        String pdir_fid = "0";
        Matcher m3 = Pattern.compile("#/list/share/([a-zA-Z0-9]{32}|[a-zA-Z0-9]{16,})").matcher(url);
        if (m3.find()) pdir_fid = m3.group(1);
        return new String[]{pwd_id, passcode, pdir_fid};
    }

    private JSONObject getStoken(String pwd_id, String passcode) throws Exception {
        JSONObject p = new JSONObject();
        p.put("pwd_id", pwd_id);
        p.put("passcode", passcode);
        return req("POST", BASE_URL + "/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc", p);
    }

    private JSONObject getDetail(String pwd_id, String stoken, String pdir_fid) throws Exception {
        JSONArray merged = new JSONArray();
        int page = 1;
        JSONObject resp = null;
        while (true) {
            String url = BASE_URL + "/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id="
                    + pwd_id + "&stoken=" + stoken + "&pdir_fid=" + pdir_fid + "&force=0"
                    + "&_page=" + page + "&_size=50&_fetch_banner=0&_fetch_share=0&_fetch_total=1"
                    + "&_sort=" + urlenc("file_type:asc,updated_at:desc") + "&ver=2&fetch_share_full_path=0";
            resp = req("GET", url, null);
            if (resp.optInt("code", -1) != 0) {
                if (resp.optInt("code") == 0) ;
                return resp;
            }
            JSONArray list = resp.optJSONObject("data").optJSONArray("list");
            int total = resp.optJSONObject("metadata").optInt("_total", 0);
            if (list != null) {
                for (int i = 0; i < list.length(); i++) merged.put(list.get(i));
            }
            if (merged.length() >= total || list == null || list.length() == 0) break;
            page++;
        }
        if (resp != null && resp.optInt("code", -1) == 0 && resp.has("data")) {
            resp.getJSONObject("data").put("list", merged);
        }
        return resp;
    }

    private JSONArray getFids(List<String> paths) throws Exception {
        JSONArray merged = new JSONArray();
        int i = 0;
        while (i < paths.size()) {
            List<String> batch = new ArrayList<>(paths.subList(i, Math.min(i + 50, paths.size())));
            JSONObject p = new JSONObject();
            p.put("file_path", new org.json.JSONArray(batch));
            p.put("namespace", "0");
            JSONObject r = req("POST", BASE_URL + "/1/clouddrive/file/info/path_list?pr=ucpro&fr=pc", p);
            if (r.optInt("code") == 0 && r.has("data")) {
                JSONArray arr = r.getJSONArray("data");
                for (int k = 0; k < arr.length(); k++) merged.put(arr.get(k));
            } else {
                break;
            }
            i += 50;
        }
        return merged;
    }

    private JSONObject mkdir(String dirPath) throws Exception {
        JSONObject p = new JSONObject();
        p.put("pdir_fid", "0");
        p.put("file_name", "");
        p.put("dir_path", dirPath);
        p.put("dir_init_lock", false);
        return req("POST", BASE_URL + "/1/clouddrive/file?pr=ucpro&fr=pc&uc_param_str=", p);
    }

    private JSONArray lsDir(String pdir_fid) throws Exception {
        JSONArray merged = new JSONArray();
        int page = 1;
        JSONObject resp = null;
        while (true) {
            String url = BASE_URL + "/1/clouddrive/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid="
                    + pdir_fid + "&_page=" + page + "&_size=50&_fetch_total=1&_fetch_sub_dirs=0"
                    + "&_sort=" + urlenc("file_type:asc,updated_at:desc") + "&_fetch_full_path=0"
                    + "&fetch_all_file=1&fetch_risk_file_name=1";
            resp = req("GET", url, null);
            if (resp.optInt("code") != 0) return null;
            JSONArray list = resp.optJSONObject("data").optJSONArray("list");
            int total = resp.optJSONObject("metadata").optInt("_total", 0);
            if (list != null) {
                for (int i = 0; i < list.length(); i++) merged.put(list.get(i));
            }
            if (merged.length() >= total || list == null || list.length() == 0) break;
            page++;
        }
        return merged;
    }

    private JSONObject saveFile(JSONArray fidList, JSONArray fidTokenList, String toFid,
                                String pwdId, String stoken) throws Exception {
        JSONObject p = new JSONObject();
        p.put("fid_list", fidList);
        p.put("fid_token_list", fidTokenList);
        p.put("to_pdir_fid", toFid);
        p.put("pwd_id", pwdId);
        p.put("stoken", stoken);
        p.put("pdir_fid", "0");
        p.put("scene", "link");
        // __dt 随机延迟
        String url = BASE_URL + "/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc&uc_param_str=&app=clouddrive&__dt="
                + (int)(Math.random() * 4000 + 1000);
        return req("POST", url, p);
    }

    private JSONObject queryTask(String taskId, int retries) throws Exception {
        JSONObject resp = null;
        for (int i = 0; i < retries; i++) {
            String url = BASE_URL + "/1/clouddrive/task?pr=ucpro&fr=pc&uc_param_str=&task_id="
                    + taskId + "&retry_index=" + i;
            resp = req("GET", url, null);
            if (resp.optInt("status") != 200) return resp;
            JSONObject data = resp.optJSONObject("data");
            if (data != null && data.optInt("status") == 2) return resp; // done
            try { Thread.sleep(500); } catch (InterruptedException e) { }
        }
        return resp;
    }

    private static String urlenc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // 重命名文件/文件夹
    private JSONObject rename(String fid, String newName) throws Exception {
        JSONObject p = new JSONObject();
        p.put("fid", fid);
        p.put("file_name", newName);
        return req("POST", BASE_URL + "/1/clouddrive/file/rename?pr=ucpro&fr=pc&uc_param_str=", p);
    }

    // 移动文件/文件夹到目标目录
    private JSONObject moveFiles(List<String> fids, String toFid) throws Exception {
        JSONObject p = new JSONObject();
        p.put("filelist", new org.json.JSONArray(fids));
        p.put("to_pdir_fid", toFid);
        p.put("exclude_fids", new org.json.JSONArray());
        p.put("action_type", 1);
        return req("POST", BASE_URL + "/1/clouddrive/file/move?pr=ucpro&fr=pc&uc_param_str=", p);
    }

    // 删除
    private JSONObject deleteFile(List<String> fids) throws Exception {
        JSONObject p = new JSONObject();
        p.put("action_type", 2);
        p.put("filelist", new org.json.JSONArray(fids));
        p.put("exclude_fids", new org.json.JSONArray());
        return req("POST", BASE_URL + "/1/clouddrive/file/delete?pr=ucpro&fr=pc&uc_param_str=", p);
    }

    // ---------- 转存单个链接 ----------
    /**
     * 转存一个分享链接到 savepath。
     * 对已存在的目标，按 xxx(1)、xxx(2) 依次累加后缀（用户指定的行为）。
     * @return true 表示成功或已经存在
     */
    public boolean saveTask(String shareurl, String savepath) {
        String[] ex = extractUrl(shareurl);
        String pwdId = ex[0];
        if (pwdId == null) { emitAsync("链接格式无法识别：" + shareurl); return false; }
        String passcode = ex[1];
        String shareSubFid = ex[2];

        try {
            JSONObject st = getStoken(pwdId, passcode);
            if (st.optInt("code") != 0) {
                emitAsync("链接无效或已失效：" + st.optString("message", "未知错误"));
                return false;
            }
            String stoken = st.optJSONObject("data").optString("stoken");

            // 分享根目录详情
            // 若链接指向分享的子目录(#/list/share/<fid>)，则只转存该子目录的内容
            String listFid = shareSubFid.equals("0") ? "0" : shareSubFid;
            JSONObject detail = getDetail(pwdId, stoken, listFid);
            if (detail.optInt("code") != 0) {
                emitAsync("获取分享内容失败：" + detail.optString("message", ""));
                return false;
            }
            JSONArray shareList = detail.optJSONObject("data").optJSONArray("list");
            if (shareList == null || shareList.length() == 0) {
                emitAsync("分享为空或已被删除");
                return false;
            }

            // 整份转存：不管是单个文件夹还是分散文件，都整体移动到目标文件夹
            // - 单个文件夹 → 文件夹整体移动（保持完整）
            // - 分散文件   → 直接移动到目标文件夹
            emitAsync("整份转存 " + shareList.length() + " 个条目");
            return directSaveAll(shareList, pwdId, stoken, savepath);
        } catch (Exception e) {
            emitAsync("转存异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private String toFidResolve(String savepath) throws Exception {
        if (savepath == null || savepath.trim().isEmpty() || savepath.equals("/")) return "0";
        String path = savepath.trim();
        JSONArray fids = getFids(java.util.Collections.singletonList(path));
        if (fids.length() > 0) return fids.optJSONObject(0).optString("fid");
        JSONObject mk = mkdir(path);
        if (mk.optInt("code") == 0) return mk.optJSONObject("data").optString("fid");
        throw new Exception("创建目录失败：" + mk.optString("message", ""));
    }

    // 整份转存，强制重新保存，重名自动累加 (1)(2)(3)…
    // 由于夸克转存 API 在目标目录已有同名文件时会静默跳过，这里采用“隔离暂存”法：
    // 1) 先整份转存到网盘里一个新的空临时文件夹（必然无冲突，必被重新保存）
    // 2) 读取临时文件夹里的条目
    // 3) 为每个条目计算它在“目标文件夹 + 本次导入条目”这个名空间下应得的 (n) 后缀并重命名
    // 4) 移动到目标文件夹
    // 5) 删除空的临时文件夹
    private boolean directSaveAll(JSONArray items, String pwdId, String stoken, String savepath) throws Exception {
        String toFid = toFidResolve(savepath);

        // 目标目录现有文件名
        JSONArray existing = lsDir(toFid);
        List<String> taken = new ArrayList<>();
        if (existing != null) {
            for (int i = 0; i < existing.length(); i++)
                taken.add(existing.optJSONObject(i).optString("file_name"));
        }

        // 1) 建临时文件夹
        String tmpName = "自动转存暂存_" + System.currentTimeMillis();
        String tmpPath = "/" + tmpName;
        JSONObject mk = mkdir(tmpPath);
        if (mk.optInt("code") != 0) {
            emitAsync("创建暂存目录失败，无法强制重新保存");
            return false;
        }
        String tmpFid = mk.optJSONObject("data").optString("fid");

        // 2) 整份转存到临时文件夹（无冲突，必然保存）
        JSONArray fidList = new JSONArray();
        JSONArray fidTokenList = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            fidList.put(it.optString("fid"));
            fidTokenList.put(it.optString("share_fid_token"));
        }
        JSONObject sv = saveFile(fidList, fidTokenList, tmpFid, pwdId, stoken);
        if (sv.optInt("code") != 0) {
            emitAsync("转存请求失败：" + sv.optString("message", ""));
            deleteFile(java.util.Collections.singletonList(tmpFid));
            return false;
        }
        emitAsync("已转存到暂存目录，等待处理…");
        queryTask(sv.optJSONObject("data").optString("task_id"), 30);

        // 3) 读取临时目录（可能含子目录，此处只处理顶层条目）
        JSONArray saved = lsDir(tmpFid);
        if (saved == null || saved.length() == 0) {
            emitAsync("转存后暂存目录为空（可能全部被跳过）");
            deleteFile(java.util.Collections.singletonList(tmpFid));
            return false;
        }
        emitAsync("重新保存到暂存目录：共 " + saved.length() + " 个条目");

        // 4) 逐个重命名(加后缀)并移动到目标
        List<String> moveIds = new ArrayList<>();
        int renamed = 0;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject it = saved.optJSONObject(i);
            if (it == null) continue;
            String oname = it.optString("file_name");
            String fid = it.optString("fid");

            // 目标已有同名 -> 加 (1)(2)...；本次也纳入名空间避免互相冲突
            String target = oname;
            if (taken.contains(target)) {
                String ext = extOf(oname);
                String base = ext.isEmpty() ? oname : oname.substring(0, oname.length() - ext.length());
                int n = 1;
                do {
                    target = base + "(" + n + ")" + ext;
                    n++;
                } while (taken.contains(target));
                rename(fid, target);
                renamed++;
            }
            taken.add(target);
            moveIds.add(fid);
        }

        // 5) 移动到目标文件夹
        JSONObject mv = moveFiles(moveIds, toFid);
        boolean moved = mv.optInt("code") == 0;
        if (!moved) emitAsync("移动到目标目录失败：" + mv.optString("message", ""));

        // 6) 删除空暂存目录
        try { deleteFile(java.util.Collections.singletonList(tmpFid)); } catch (Exception e) { }

        emitAsync("✅ 转存完成：成功 " + moveIds.size() + " 个，重命名(加后缀) " + renamed + " 个");
        return moved && moveIds.size() > 0;
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i <= 0) return "";
        return name.substring(i);
    }

    public String getLogText() { return log.toString(); }
    public void clearLog() { log.setLength(0); }
}