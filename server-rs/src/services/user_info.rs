use tonic::{Request, Response, Status};
use tracing::info;

use crate::proto::account::*;
use crate::proto::account::user_information_service_server::UserInformationService;

pub struct UserInformationServiceImpl;

#[tonic::async_trait]
impl UserInformationService for UserInformationServiceImpl {
    async fn get_user_personal_details(
        &self,
        _request: Request<()>,
    ) -> Result<Response<PersonalDetailsResponse>, Status> {
        info!(">>> UserInformation.GetUserPersonalDetails");
        Ok(Response::new(PersonalDetailsResponse {
            preferred_name: "User".into(),
            account_info: Some(AccountInfo {
                email: "user@local.pin".into(),
                authorization_type: AuthorizationType::Dac as i32,
            }),
        }))
    }
}
