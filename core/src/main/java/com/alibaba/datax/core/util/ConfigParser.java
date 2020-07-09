package com.alibaba.datax.core.util;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.util.container.CoreConstant;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ConfigParser {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigParser.class);


    /**
     * 指定Job配置路径，ConfigParser会解析Job、Plugin、Core全部信息，并以Configuration返回
     * configuration解析包括三部分的配置解析合并解析结果并返回，分别是：
     *
     * 1、解析job的配置信息，由启动参数指定job.json文件。
     * 2、解析DataX自带配置信息，由默认指定的core.json文件。
     * 3、解析读写插件配置信息，由job.json指定的reader和writer插件信息
     */
    public static Configuration parse(final String jobPath) {
        // 加载任务的指定的配置文件，这个配置是有固定的json的固定模板格式的
        Configuration configuration = ConfigParser.parseJobConfig(jobPath);
        // 合并conf/core.json的配置文件
        configuration.merge(
                ConfigParser.parseCoreConfig(CoreConstant.DATAX_CONF_PATH),
                false);
        // todo config优化，只捕获需要的plugin
        // 固定的节点路径 job.content[0].reader.name
        String readerPluginName = configuration.getString(
                CoreConstant.DATAX_JOB_CONTENT_READER_NAME);
        // 固定的节点路径 job.content[0].writer.name
        String writerPluginName = configuration.getString(
                CoreConstant.DATAX_JOB_CONTENT_WRITER_NAME);
        // 固定的节点路径 job.preHandler.pluginName
        String preHandlerName = configuration.getString(
                CoreConstant.DATAX_JOB_PREHANDLER_PLUGINNAME);
        // 固定的节点路径 job.postHandler.pluginName
        String postHandlerName = configuration.getString(
                CoreConstant.DATAX_JOB_POSTHANDLER_PLUGINNAME);
        // 添加读写插件的列表待加载
        Set<String> pluginList = new HashSet<String>();
        pluginList.add(readerPluginName);
        pluginList.add(writerPluginName);

        if (StringUtils.isNotEmpty(preHandlerName)) {
            pluginList.add(preHandlerName);
        }
        if (StringUtils.isNotEmpty(postHandlerName)) {
            pluginList.add(postHandlerName);
        }
        try {
            // parsePluginConfig(new ArrayList<String>(pluginList))加载指定的插件的配置信息，并且和全局的配置文件进行合并
            configuration.merge(parsePluginConfig(new ArrayList<String>(pluginList)), false);
        } catch (Exception e) {
            //吞掉异常，保持log干净。这里message足够。
            LOG.warn(String.format("插件[%s,%s]加载失败，1s后重试... Exception:%s ", readerPluginName, writerPluginName, e.getMessage()));
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e1) {
                //
            }
            configuration.merge(parsePluginConfig(new ArrayList<String>(pluginList)), false);
        }
        // configuration整合了三方的配置，包括 任务配置、core核心配置、指定插件的配置
        return configuration;
    }

    private static Configuration parseCoreConfig(final String path) {
        return Configuration.from(new File(path));
    }

    public static Configuration parseJobConfig(final String path) {
        String jobContent = getJobContent(path);
        Configuration config = Configuration.from(jobContent);

        return SecretUtil.decryptSecretKey(config);
    }

    private static String getJobContent(String jobResource) {
        String jobContent;

        boolean isJobResourceFromHttp = jobResource.trim().toLowerCase().startsWith("http");


        if (isJobResourceFromHttp) {
            //设置httpclient的 HTTP_TIMEOUT_INMILLIONSECONDS
            Configuration coreConfig = ConfigParser.parseCoreConfig(CoreConstant.DATAX_CONF_PATH);
            int httpTimeOutInMillionSeconds = coreConfig.getInt(
                    CoreConstant.DATAX_CORE_DATAXSERVER_TIMEOUT, 5000);
            HttpClientUtil.setHttpTimeoutInMillionSeconds(httpTimeOutInMillionSeconds);

            HttpClientUtil httpClientUtil = new HttpClientUtil();
            try {
                URL url = new URL(jobResource);
                HttpGet httpGet = HttpClientUtil.getGetRequest();
                httpGet.setURI(url.toURI());

                jobContent = httpClientUtil.executeAndGetWithFailedRetry(httpGet, 1, 1000l);
            } catch (Exception e) {
                throw DataXException.asDataXException(FrameworkErrorCode.CONFIG_ERROR, "获取作业配置信息失败:" + jobResource, e);
            }
        } else {
            // jobResource 是本地文件绝对路径
            try {
                jobContent = FileUtils.readFileToString(new File(jobResource));
            } catch (IOException e) {
                throw DataXException.asDataXException(FrameworkErrorCode.CONFIG_ERROR, "获取作业配置信息失败:" + jobResource, e);
            }
        }

        if (jobContent == null) {
            throw DataXException.asDataXException(FrameworkErrorCode.CONFIG_ERROR, "获取作业配置信息失败:" + jobResource);
        }
        return jobContent;
    }

    public static Configuration parsePluginConfig(List<String> wantPluginNames) {
        // 创建一个空的配置信息对象
        Configuration configuration = Configuration.newDefault();

        Set<String> replicaCheckPluginSet = new HashSet<String>();
        int complete = 0;
        // 所有的reader在/plugin/reader目录，遍历获取所有reader的目录
        // 获取待加载插件的配资信息，并合并到上面创建的空配置对象
        // ${datax.home}/plugin/reader
        for (final String each : ConfigParser
                .getDirAsList(CoreConstant.DATAX_PLUGIN_READER_HOME)) {
            // 解析单个reader目录，eachReaderConfig保存的是key是plugin.reader.pluginname，value是对应的plugin.json内容
            Configuration eachReaderConfig = ConfigParser.parseOnePluginConfig(each, "reader", replicaCheckPluginSet, wantPluginNames);
            if (eachReaderConfig != null) {
                // 采用覆盖式的合并
                configuration.merge(eachReaderConfig, true);
                complete += 1;
            }
        }
        // 所有的reader在/plugin/writer目录，遍历获取所有reader的目录
        // 获取待加载插件的配资信息，并合并到上面创建的配置对象
        // ${datax.home}/plugin/writer
        for (final String each : ConfigParser
                .getDirAsList(CoreConstant.DATAX_PLUGIN_WRITER_HOME)) {
            Configuration eachWriterConfig = ConfigParser.parseOnePluginConfig(each, "writer", replicaCheckPluginSet, wantPluginNames);
            if (eachWriterConfig != null) {
                configuration.merge(eachWriterConfig, true);
                complete += 1;
            }
        }

        if (wantPluginNames != null && wantPluginNames.size() > 0 && wantPluginNames.size() != complete) {
            throw DataXException.asDataXException(FrameworkErrorCode.PLUGIN_INIT_ERROR, "插件加载失败，未完成指定插件加载:" + wantPluginNames);
        }

        return configuration;
    }


    public static Configuration parseOnePluginConfig(final String path,
                                                     final String type,
                                                     Set<String> pluginSet, List<String> wantPluginNames) {
        String filePath = path + File.separator + "plugin.json";
        Configuration configuration = Configuration.from(new File(filePath));

        String pluginPath = configuration.getString("path");
        String pluginName = configuration.getString("name");
        if (!pluginSet.contains(pluginName)) {
            pluginSet.add(pluginName);
        } else {
            throw DataXException.asDataXException(FrameworkErrorCode.PLUGIN_INIT_ERROR, "插件加载失败,存在重复插件:" + filePath);
        }

        //不是想要的插件，返回null
        if (wantPluginNames != null && wantPluginNames.size() > 0 && !wantPluginNames.contains(pluginName)) {
            return null;
        }

        boolean isDefaultPath = StringUtils.isBlank(pluginPath);
        if (isDefaultPath) {
            configuration.set("path", path);
        }

        Configuration result = Configuration.newDefault();

        result.set(
                String.format("plugin.%s.%s", type, pluginName),
                configuration.getInternal());

        return result;
    }

    private static List<String> getDirAsList(String path) {
        List<String> result = new ArrayList<String>();

        String[] paths = new File(path).list();
        if (null == paths) {
            return result;
        }

        for (final String each : paths) {
            result.add(path + File.separator + each);
        }

        return result;
    }

}
