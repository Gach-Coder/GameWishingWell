// GitHub Actions（美国 runner）访问阿里云镜像经常失败导致依赖解析中断，
// CI 上优先官方源；本地（国内网络）优先阿里云加速。GITHUB_ACTIONS 是 runner 内置变量。
// 注意：pluginManagement 块独立编译，不能引用脚本顶层变量，需在块内直接判断。

pluginManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "GameWishingWell"
include(":app")
