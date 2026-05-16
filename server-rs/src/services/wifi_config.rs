use tonic::{Request, Response, Status};
use tracing::info;

use crate::proto::account::wifi_config_service_server::WifiConfigService;
use crate::proto::account::*;

pub struct WifiConfigServiceImpl;

#[tonic::async_trait]
impl WifiConfigService for WifiConfigServiceImpl {
    async fn list_secure_wifi_configs(
        &self,
        _request: Request<ListSecureWifiConfigsRequest>,
    ) -> Result<Response<ListSecureWifiConfigsResponse>, Status> {
        info!(">>> WifiConfig.ListSecureWifiConfigs");
        Ok(Response::new(ListSecureWifiConfigsResponse {
            configs: vec![],
        }))
    }
}
